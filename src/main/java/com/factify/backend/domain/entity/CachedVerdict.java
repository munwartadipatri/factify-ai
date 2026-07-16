package com.factify.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "cached_verdicts",
        indexes = {
                @Index(name = "idx_cached_verdicts_original_text", columnList = "original_text")
        }
)
public class CachedVerdict {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "original_text", nullable = false, columnDefinition = "text")
    private String originalText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "text_metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> textMetadata = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verdict_payload", nullable = false, columnDefinition = "jsonb")
    private String verdictPayload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CachedVerdict() {
    }

    public CachedVerdict(String originalText, Map<String, Object> textMetadata, String verdictPayload) {
        this.originalText = Objects.requireNonNull(originalText, "originalText is required");
        this.textMetadata = textMetadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(textMetadata);
        this.verdictPayload = Objects.requireNonNull(verdictPayload, "verdictPayload is required");
    }

    public UUID getId() {
        return id;
    }

    public String getOriginalText() {
        return originalText;
    }

    public void setOriginalText(String originalText) {
        this.originalText = Objects.requireNonNull(originalText, "originalText is required");
    }

    public Map<String, Object> getTextMetadata() {
        return textMetadata;
    }

    public void setTextMetadata(Map<String, Object> textMetadata) {
        this.textMetadata = textMetadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(textMetadata);
    }

    public String getVerdictPayload() {
        return verdictPayload;
    }

    public void setVerdictPayload(String verdictPayload) {
        this.verdictPayload = Objects.requireNonNull(verdictPayload, "verdictPayload is required");
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
