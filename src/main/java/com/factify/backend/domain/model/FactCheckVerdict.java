package com.factify.backend.domain.model;

import java.util.List;

public record FactCheckVerdict(
        VerdictRating rating,
        double confidenceScore,
        String conciseSummary,
        List<ClaimAnalysis> analyzedClaims,
        List<String> trustedSources
) {
    public FactCheckVerdict {
        analyzedClaims = analyzedClaims == null ? List.of() : List.copyOf(analyzedClaims);
        trustedSources = trustedSources == null ? List.of() : List.copyOf(trustedSources);
    }
}
