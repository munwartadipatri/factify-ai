package com.factify.backend.service;

import com.factify.backend.domain.entity.CachedVerdict;
import com.factify.backend.domain.model.FactCheckVerdict;
import com.factify.backend.repository.CachedVerdictRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.dao.DataRetrievalFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private static final double CACHE_HIT_THRESHOLD = 0.92;
    private static final int CACHE_SCHEMA_VERSION = 11;
    private static final String CACHE_KIND = "fact-check-verdict";
    private static final String SCHEMA_VERSION_METADATA_KEY = "schemaVersion";
    private static final String VERDICT_PAYLOAD_METADATA_KEY = "verdictPayload";

    private final VectorStore vectorStore;
    private final JsonMapper jsonMapper;
    private final CachedVerdictRepository cachedVerdictRepository;

    public SemanticCacheService(
            VectorStore vectorStore,
            JsonMapper jsonMapper,
            CachedVerdictRepository cachedVerdictRepository
    ) {
        this.vectorStore = vectorStore;
        this.jsonMapper = jsonMapper;
        this.cachedVerdictRepository = cachedVerdictRepository;
    }

    public Optional<FactCheckVerdict> checkCache(String incomingMessage) {
        if (!StringUtils.hasText(incomingMessage)) {
            log.debug("Semantic cache lookup skipped because message is blank.");
            return Optional.empty();
        }

        log.debug(
                "Semantic cache lookup started. messageLength={}, threshold={}",
                incomingMessage.length(),
                CACHE_HIT_THRESHOLD
        );

        SearchRequest request = SearchRequest.builder()
                .query(incomingMessage)
                .topK(1)
                .similarityThreshold(CACHE_HIT_THRESHOLD)
                .build();

        List<Document> matches = vectorStore.similaritySearch(request);
        log.info("Semantic cache lookup completed. matches={}", matches.size());

        Optional<FactCheckVerdict> verdict = matches.stream()
                .findFirst()
                .flatMap(this::deserializeVerdict);

        log.info("Semantic cache {}.", verdict.isPresent() ? "hit" : "miss");
        return verdict;
    }

    public void saveToCache(String message, FactCheckVerdict verdict) {
        if (!StringUtils.hasText(message) || verdict == null) {
            log.debug("Semantic cache save skipped. hasMessage={}, hasVerdict={}", StringUtils.hasText(message), verdict != null);
            return;
        }

        log.info(
                "Saving verdict to semantic cache. messageLength={}, rating={}, claims={}, sources={}",
                message.length(),
                verdict.rating(),
                verdict.analyzedClaims() == null ? 0 : verdict.analyzedClaims().size(),
                verdict.trustedSources() == null ? 0 : verdict.trustedSources().size()
        );

        String verdictPayload = serialize(verdict);
        Map<String, Object> metadata = Map.of(
                "cacheKind", CACHE_KIND,
                SCHEMA_VERSION_METADATA_KEY, CACHE_SCHEMA_VERSION,
                VERDICT_PAYLOAD_METADATA_KEY, verdictPayload
        );
        Document document = new Document(
                UUID.randomUUID().toString(),
                message,
                metadata
        );

        vectorStore.add(List.of(document));
        cachedVerdictRepository.save(new CachedVerdict(message, metadata, verdictPayload));
        log.info("Verdict saved to vector store and cached_verdicts audit table.");
    }

    private Optional<FactCheckVerdict> deserializeVerdict(Document document) {
        if (!isCurrentSchema(document)) {
            log.info("Ignoring semantic cache document due to schema mismatch. metadata={}", document.getMetadata());
            return Optional.empty();
        }

        Object metadataPayload = document.getMetadata().get(VERDICT_PAYLOAD_METADATA_KEY);
        if (metadataPayload instanceof String json && StringUtils.hasText(json)) {
            return readVerdict(json);
        }

        String documentText = document.getText();
        if (StringUtils.hasText(documentText) && documentText.stripLeading().startsWith("{")) {
            return readVerdict(documentText);
        }

        log.warn("Semantic cache document did not contain a readable verdict payload. metadataKeys={}", document.getMetadata().keySet());
        return Optional.empty();
    }

    private boolean isCurrentSchema(Document document) {
        Object schemaVersion = document.getMetadata().get(SCHEMA_VERSION_METADATA_KEY);
        if (schemaVersion instanceof Number number) {
            return number.intValue() == CACHE_SCHEMA_VERSION;
        }
        if (schemaVersion instanceof String value && StringUtils.hasText(value)) {
            try {
                return Integer.parseInt(value) == CACHE_SCHEMA_VERSION;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return false;
    }

    private Optional<FactCheckVerdict> readVerdict(String json) {
        try {
            return Optional.of(jsonMapper.readValue(json, FactCheckVerdict.class));
        } catch (RuntimeException ex) {
            log.warn("Failed to deserialize cached verdict payload.", ex);
            return Optional.empty();
        }
    }

    private String serialize(FactCheckVerdict verdict) {
        try {
            return jsonMapper.writeValueAsString(verdict);
        } catch (RuntimeException ex) {
            log.error("Failed to serialize verdict for semantic cache. rating={}", verdict.rating(), ex);
            throw new DataRetrievalFailureException("Failed to serialize fact-check verdict for semantic cache.", ex);
        }
    }
}
