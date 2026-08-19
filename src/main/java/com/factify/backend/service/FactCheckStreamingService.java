package com.factify.backend.service;

import com.factify.backend.domain.model.FactCheckVerdict;
import org.springframework.ai.content.Media;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class FactCheckStreamingService {
    private static final Logger log = LoggerFactory.getLogger(FactCheckStreamingService.class);

    private final FactCheckAgent factCheckAgent;
    private final JsonMapper jsonMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public FactCheckStreamingService(FactCheckAgent factCheckAgent, JsonMapper jsonMapper) {
        this.factCheckAgent = factCheckAgent;
        this.jsonMapper = jsonMapper;
    }

    public void streamText(String message, SseEmitter emitter) {
        executor.execute(() -> run(emitter, () -> factCheckAgent.verifyMessage(message, progress(emitter))));
    }

    public void streamMedia(String message, List<Media> media, SseEmitter emitter) {
        executor.execute(() -> run(emitter, () -> factCheckAgent.verifyMessage(message, media, progress(emitter))));
    }

    private FactCheckAgent.ProgressListener progress(SseEmitter emitter) {
        return (stage, message) -> send(emitter, "status", new StreamStatus(stage, message));
    }

    private void run(SseEmitter emitter, VerdictSupplier supplier) {
        try {
            send(emitter, "status", new StreamStatus("STARTED", "Factify verification started."));
            FactCheckVerdict verdict = supplier.get();
            send(emitter, "verdict", verdict);
            send(emitter, "complete", new StreamStatus("COMPLETE", "Verification complete."));
            emitter.complete();
        } catch (Exception ex) {
            log.error("Streaming fact-check failed.", ex);
            send(emitter, "error", new StreamError("Fact-check verification failed."));
            emitter.completeWithError(ex);
        }
    }

    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(jsonMapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not write fact-check stream event.", ex);
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private interface VerdictSupplier {
        FactCheckVerdict get();
    }

    public record StreamStatus(String stage, String message) { }

    public record StreamError(String message) { }
}
