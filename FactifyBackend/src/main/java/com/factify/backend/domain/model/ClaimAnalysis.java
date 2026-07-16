package com.factify.backend.domain.model;

public record ClaimAnalysis(
        String claim,
        VerdictRating claimRating,
        String explanation
) {
}
