package com.factify.backend.controller;

import com.factify.backend.domain.model.FactCheckVerdict;
import com.factify.backend.service.FactCheckAgent;
import com.factify.backend.service.GoogleFactCheckService;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/verify")
@CrossOrigin(
        origins = {
                "http://localhost:3000",
                "http://localhost:5173",
                "http://127.0.0.1:3000",
                "http://127.0.0.1:5173"
        }
)
public class FactCheckController {

    private static final Logger log = LoggerFactory.getLogger(FactCheckController.class);

    private final FactCheckAgent factCheckAgent;
    private final GoogleFactCheckService googleFactCheckService;

    public FactCheckController(
            FactCheckAgent factCheckAgent,
            GoogleFactCheckService googleFactCheckService
    ) {
        this.factCheckAgent = factCheckAgent;
        this.googleFactCheckService = googleFactCheckService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> checkMessage(@RequestBody VerifyMessageRequest request) {
        log.info(
                "Verify JSON request received. hasMessage={}, messageLength={}",
                request != null && StringUtils.hasText(request.message()),
                request == null || request.message() == null ? 0 : request.message().length()
        );

        if (request == null || !StringUtils.hasText(request.message())) {
            return ResponseEntity
                    .badRequest()
                    .body(ErrorResponse.validation("Field 'message' must not be null or empty."));
        }

        try {
            FactCheckVerdict verdict = factCheckAgent.verifyMessage(request.message());
            return ResponseEntity.ok(verdict);
        } catch (Exception ex) {
            log.error("Error during JSON fact-check verification.", ex);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.serverError("Fact-check verification failed."));
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> checkMessage(
            @RequestPart(value = "message", required = false) String message,
            @RequestPart(value = "files", required = false) List<MultipartFile> files
    ) {
        log.info(
                "Verify multipart request received. hasMessage={}, messageLength={}, uploadedFiles={}",
                StringUtils.hasText(message),
                message == null ? 0 : message.length(),
                files == null ? 0 : files.size()
        );

        boolean hasMessage = StringUtils.hasText(message);
        boolean hasFiles = files != null && files.stream().anyMatch(file -> !file.isEmpty());
        if (!hasMessage && !hasFiles) {
            return ResponseEntity
                    .badRequest()
                    .body(ErrorResponse.validation("Field 'message' or an image attachment is required."));
        }

        try {
            FactCheckVerdict verdict = factCheckAgent.verifyMessage(
                    message,
                    toImageMedia(files)
            );
            return ResponseEntity.ok(verdict);
        } catch (Exception ex) {
            log.error("Error during multipart fact-check verification.", ex);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.serverError("Fact-check verification failed."));
        }
    }

    private List<Media> toImageMedia(List<MultipartFile> files) {
        if (files == null) {
            log.debug("No files supplied in multipart request.");
            return List.of();
        }

        List<Media> media = files.stream()
                .filter(file -> !file.isEmpty())
                .peek(file -> log.info(
                        "Uploaded file received. name={}, contentType={}, sizeBytes={}",
                        file.getOriginalFilename(),
                        file.getContentType(),
                        file.getSize()
                ))
                .filter(file -> {
                    boolean isImage = file.getContentType() != null && file.getContentType().startsWith("image/");
                    if (!isImage) {
                        log.warn(
                                "Ignoring non-image upload. name={}, contentType={}",
                                file.getOriginalFilename(),
                                file.getContentType()
                        );
                    }
                    return isImage;
                })
                .map(this::toMedia)
                .toList();

        log.info("Converted uploaded images to Spring AI media. mediaCount={}", media.size());
        return media;
    }

    private Media toMedia(MultipartFile file) {
        try {
            log.debug(
                    "Converting uploaded image to media. name={}, contentType={}, sizeBytes={}",
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize()
            );
            return Media.builder()
                    .mimeType(org.springframework.util.MimeTypeUtils.parseMimeType(
                            Objects.requireNonNull(file.getContentType())
                    ))
                    .data(new ByteArrayResource(file.getBytes()) {
                        @Override
                        public String getFilename() {
                            return file.getOriginalFilename();
                        }
                    })
                    .name(file.getOriginalFilename())
                    .build();
        } catch (IOException ex) {
            log.error("Failed to read uploaded image. name={}", file.getOriginalFilename(), ex);
            throw new IllegalArgumentException("Could not read uploaded file.", ex);
        }
    }

    @GetMapping("/fact-check-search")
    public ResponseEntity<?> searchFactCheckApi(@RequestParam("query") String query) {
        if (!StringUtils.hasText(query)) {
            return ResponseEntity
                    .badRequest()
                    .body(ErrorResponse.validation("Query parameter 'query' must not be null or empty."));
        }

        try {
            List<GoogleFactCheckService.FactCheckEvidence> evidence = googleFactCheckService.search(query);
            return ResponseEntity.ok(new FactCheckSearchResponse(evidence.size(), evidence));
        } catch (Exception ex) {
            log.error("Error during Google Fact Check diagnostic lookup.", ex);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.serverError("Google Fact Check diagnostic lookup failed."));
        }
    }

    public record VerifyMessageRequest(String message) {
    }

    public record FactCheckSearchResponse(
            int matches,
            List<GoogleFactCheckService.FactCheckEvidence> evidence
    ) {
    }

    public record ErrorResponse(
            Instant timestamp,
            int status,
            String error,
            String message
    ) {
        static ErrorResponse validation(String message) {
            return new ErrorResponse(
                    Instant.now(),
                    HttpStatus.BAD_REQUEST.value(),
                    HttpStatus.BAD_REQUEST.getReasonPhrase(),
                    message
            );
        }

        static ErrorResponse serverError(String message) {
            return new ErrorResponse(
                    Instant.now(),
                    HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                    message
            );
        }
    }
}
