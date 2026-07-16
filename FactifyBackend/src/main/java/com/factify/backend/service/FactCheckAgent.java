package com.factify.backend.service;

import com.factify.backend.domain.model.ClaimAnalysis;
import com.factify.backend.domain.model.FactCheckVerdict;
import com.factify.backend.domain.model.VerdictRating;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.dao.DataRetrievalFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class FactCheckAgent {

    private static final Logger log = LoggerFactory.getLogger(FactCheckAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are an elite, unbiased fact-checking agent. Deconstruct the given message into atomic claims, evaluate them based on known data or structured search realities, and provide a strict structured verdict matching the requested payload format.
            """;

    private static final String USER_PROMPT_TEMPLATE = """
            Fact-check the following message.

            Current date:
            {currentDate}

            Message:
            {incomingMessage}

            Google Fact Check API evidence:
            {factCheckEvidence}

            If images are attached, first extract every visible textual claim from the image content.
            Treat those extracted image claims as part of the message to fact-check.
            If Google Fact Check API evidence is present and relevant to the claim, use it as primary evidence.
            If the evidence is unrelated to the claim, ignore it and say the claim is unverified unless you have strong support.
            If Google Fact Check API evidence says no matching claim reviews were found, do not provide any trustedSources URLs unless the user supplied those exact URLs in the message.
            Use the Current date above for all time-sensitive claims.
            Never say a past date has not occurred if it is before the Current date.
            For claims about current political offices or current office holders, avoid stale model knowledge.
            If no verified evidence is available for a current-office-holder claim, lower the confidence and explain the uncertainty.
            For emergency numbers, helplines, medical, legal, financial, or public-safety instructions, do not return high confidence unless trusted source URLs are available.
            If no trusted source URL supports a sensitive claim, use UNVERIFIED or a low-confidence rating and explain that verified evidence is missing.

            Return exactly one JSON object with these top-level fields:
            - rating: one of TRUE, FALSE, MISLEADING, UNVERIFIED.
            - confidenceScore: a decimal number between 0.0 and 1.0.
            - conciseSummary: a short human-readable summary.
            - analyzedClaims: an array of claim analysis objects.
            - trustedSources: an array of full HTTPS URLs.

            Each analyzedClaims item must contain these fields:
            - claim: the atomic claim text.
            - claimRating: one of TRUE, FALSE, MISLEADING, UNVERIFIED.
            - explanation: why this claim received this rating.

            Never return a top-level array.
            Never use keys named "verdict" or "sources"; use "rating", "claimRating", and "trustedSources".
            Return raw JSON only. Do not wrap the JSON in Markdown, code fences, backticks, prose, or explanations.
            Use one of these rating values exactly: TRUE, FALSE, MISLEADING, UNVERIFIED.
            The confidenceScore must be between 0.0 and 1.0.
            The trustedSources field must contain direct, trustworthy source links as full HTTPS URLs.
            Prefer URLs from the Google Fact Check API evidence when they directly support the verdict.
            Prefer official government, institution, primary-source, reputable news, or fact-checking URLs.
            Only include URLs that you are confident exist and directly support the verdict.
            Do not fabricate URLs, use homepage-only links, search-result pages, or links unrelated to the specific claim.
            Do not include organization homepages as trustedSources unless the exact page directly verifies the claim.
            Do not put source names, titles, domains without protocol, or explanatory text in trustedSources.
            If no trustworthy URL can be identified, return an empty trustedSources list.
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final SemanticCacheService semanticCacheService;
    private final SourceLinkValidator sourceLinkValidator;
    private final GoogleFactCheckService googleFactCheckService;
    private final JsonMapper jsonMapper;

    public FactCheckAgent(
            ChatClient.Builder chatClientBuilder,
            SemanticCacheService semanticCacheService,
            SourceLinkValidator sourceLinkValidator,
            GoogleFactCheckService googleFactCheckService,
            JsonMapper jsonMapper
    ) {
        this.chatClientBuilder = chatClientBuilder;
        this.semanticCacheService = semanticCacheService;
        this.sourceLinkValidator = sourceLinkValidator;
        this.googleFactCheckService = googleFactCheckService;
        this.jsonMapper = jsonMapper;
    }

    public FactCheckVerdict verifyMessage(String incomingMessage) {
        if (!StringUtils.hasText(incomingMessage)) {
            throw new IllegalArgumentException("incomingMessage must not be blank");
        }

        log.info("Fact-check request received. mode=text, messageLength={}", incomingMessage.length());
        return semanticCacheService.checkCache(incomingMessage)
                .orElseGet(() -> generateAndCacheVerdict(incomingMessage));
    }

    public FactCheckVerdict verifyMessage(String incomingMessage, List<Media> attachedMedia) {
        String normalizedMessage = StringUtils.hasText(incomingMessage)
                ? incomingMessage
                : "Extract the claims visible in the attached image content and fact-check them.";

        if (attachedMedia == null || attachedMedia.isEmpty()) {
            return verifyMessage(normalizedMessage);
        }

        log.info(
                "Fact-check request received. mode=multimodal, messageLength={}, mediaCount={}",
                normalizedMessage.length(),
                attachedMedia.size()
        );
        return generateVerdict(normalizedMessage, attachedMedia);
    }

    private FactCheckVerdict generateAndCacheVerdict(String incomingMessage) {
        FactCheckVerdict verdict = generateVerdict(incomingMessage, List.of());
        semanticCacheService.saveToCache(incomingMessage, verdict);
        return verdict;
    }

    private FactCheckVerdict
    generateVerdict(String incomingMessage, List<Media> attachedMedia) {
        log.debug(
                "Calling fact-check model. messageLength={}, mediaCount={}",
                incomingMessage.length(),
                attachedMedia.size()
        );

        List<GoogleFactCheckService.FactCheckEvidence> factCheckEvidence = googleFactCheckService.search(incomingMessage);
        String formattedEvidence = googleFactCheckService.formatForPrompt(factCheckEvidence);
        String currentDate = LocalDate.now(ZoneId.systemDefault()).toString();
        log.debug("Google Fact Check evidence for prompt: {}", preview(formattedEvidence, 1500));

        String response = chatClientBuilder
                .build()
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(userSpec -> userSpec
                        .text(USER_PROMPT_TEMPLATE)
                        .media(attachedMedia.toArray(Media[]::new))
                        .param("currentDate", currentDate)
                        .param("incomingMessage", incomingMessage)
                        .param("factCheckEvidence", formattedEvidence))
                .call()
                .content();

        log.debug("Model raw response preview: {}", preview(response, 1200));
        FactCheckVerdict parsedVerdict = parseVerdict(response);
        FactCheckVerdict validatedVerdict = sourceLinkValidator.validate(parsedVerdict);
        FactCheckVerdict evidenceSafeVerdict = applyEvidenceSafetyChecks(incomingMessage, factCheckEvidence, validatedVerdict);
        log.info(
                "Trusted source validation completed. before={}, after={}",
                parsedVerdict.trustedSources().size(),
                evidenceSafeVerdict.trustedSources().size()
        );
        return evidenceSafeVerdict;
    }

    private FactCheckVerdict applyEvidenceSafetyChecks(
            String incomingMessage,
            List<GoogleFactCheckService.FactCheckEvidence> factCheckEvidence,
            FactCheckVerdict verdict
    ) {
        if (verdict == null || !verdict.trustedSources().isEmpty()) {
            return verdict;
        }

        boolean hasGoogleEvidence = factCheckEvidence != null && !factCheckEvidence.isEmpty();
        boolean sensitiveClaim = isSensitiveUnsupportedClaim(incomingMessage, verdict);
        if (!sensitiveClaim && hasGoogleEvidence) {
            return verdict;
        }

        double cappedConfidence = Math.min(verdict.confidenceScore(), sensitiveClaim ? 0.45 : 0.65);
        VerdictRating saferRating = sensitiveClaim ? VerdictRating.UNVERIFIED : verdict.rating();
        String summary = sensitiveClaim
                ? "No verified source links were found for this public-safety claim, so Factify cannot confirm the correct instruction."
                : verdict.conciseSummary();

        List<ClaimAnalysis> adjustedClaims = verdict.analyzedClaims().stream()
                .map(claim -> new ClaimAnalysis(
                        claim.claim(),
                        sensitiveClaim ? VerdictRating.UNVERIFIED : claim.claimRating(),
                        appendMissingEvidenceNote(claim.explanation(), sensitiveClaim)
                ))
                .toList();

        log.warn(
                "Downgrading unsupported verdict confidence. sensitiveClaim={}, hadGoogleEvidence={}, originalRating={}, saferRating={}, originalConfidence={}, cappedConfidence={}",
                sensitiveClaim,
                hasGoogleEvidence,
                verdict.rating(),
                saferRating,
                verdict.confidenceScore(),
                cappedConfidence
        );

        return new FactCheckVerdict(
                saferRating,
                cappedConfidence,
                summary,
                adjustedClaims,
                verdict.trustedSources()
        );
    }

    private boolean isSensitiveUnsupportedClaim(String incomingMessage, FactCheckVerdict verdict) {
        String combinedText = (incomingMessage + " " + verdict.conciseSummary() + " " + verdict.analyzedClaims())
                .toLowerCase();
        return combinedText.matches(".*\\b(ambulance|emergency|helpline|hotline|police|fire|medical|hospital|doctor|medicine|dose|lawyer|legal|bank|payment|upi|account|password|otp)\\b.*")
                || combinedText.matches(".*\\b\\d{3,5}\\b.*\\b(call|dial|number|helpline|emergency)\\b.*")
                || combinedText.matches(".*\\b(call|dial|number|helpline|emergency)\\b.*\\b\\d{3,5}\\b.*");
    }

    private String appendMissingEvidenceNote(String explanation, boolean sensitiveClaim) {
        if (!sensitiveClaim) {
            return explanation;
        }
        String base = StringUtils.hasText(explanation) ? explanation.strip() : "The claim could not be verified from trusted source links.";
        String note = " No verified source link was available, so this public-safety claim should be checked with an official source before acting on it.";
        return base.endsWith(note.strip()) ? base : base + note;
    }

    private FactCheckVerdict parseVerdict(String response) {
        String json = extractJsonPayload(response);
        log.debug("Extracted verdict JSON preview: {}", preview(json, 1200));
        try {
            FactCheckVerdict verdict = parseCanonicalOrAlternateVerdict(json);
            log.info(
                    "Fact-check verdict parsed. rating={}, confidence={}, claims={}, sources={}",
                    verdict.rating(),
                    verdict.confidenceScore(),
                    verdict.analyzedClaims() == null ? 0 : verdict.analyzedClaims().size(),
                    verdict.trustedSources() == null ? 0 : verdict.trustedSources().size()
            );
            return verdict;
        } catch (RuntimeException ex) {
            log.error("Failed to parse verdict JSON. jsonPreview={}", preview(json, 2000), ex);
            throw new DataRetrievalFailureException("Failed to parse fact-check verdict JSON from model response.", ex);
        }
    }

    private FactCheckVerdict parseCanonicalOrAlternateVerdict(String json) {
        JsonNode root = jsonMapper.readTree(json);
        if (root.isArray()) {
            log.warn("Model returned top-level array instead of FactCheckVerdict object. Normalizing array response.");
            return normalizeArrayVerdict(root);
        }

        if (looksLikeAlternateObject(root)) {
            log.warn("Model returned object with alternate keys. Normalizing object response.");
            return normalizeObjectVerdict(root);
        }

        FactCheckVerdict verdict = jsonMapper.treeToValue(root, FactCheckVerdict.class);
        if (verdict.rating() == null) {
            log.warn("Model returned FactCheckVerdict object without rating. jsonPreview={}", preview(json, 1200));
        }
        return verdict;
    }

    private boolean looksLikeAlternateObject(JsonNode root) {
        return root.isObject()
                && (!root.has("rating")
                || root.has("verdict")
                || root.has("claim")
                || root.has("sources")
                || root.has("claims"));
    }

    private FactCheckVerdict normalizeArrayVerdict(JsonNode root) {
        List<ClaimAnalysis> claims = new ArrayList<>();
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        List<VerdictRating> ratings = new ArrayList<>();
        double confidenceTotal = 0.0;
        int confidenceCount = 0;

        root.forEach(node -> {
            VerdictRating rating = toRating(textValue(node, "claimRating", "rating", "verdict"));
            String claim = textValue(node, "claim");
            String explanation = textValue(node, "explanation", "reason", "summary");
            claims.add(new ClaimAnalysis(claim, rating, explanation));
            ratings.add(rating);
            collectSources(node, sources);
        });

        for (JsonNode node : root) {
            if (node.has("confidenceScore") && node.get("confidenceScore").isNumber()) {
                confidenceTotal += node.get("confidenceScore").doubleValue();
                confidenceCount++;
            }
        }

        VerdictRating overallRating = aggregateRating(ratings);
        double confidence = confidenceCount == 0 ? 0.0 : confidenceTotal / confidenceCount;
        String summary = "Factify analyzed " + claims.size() + " claim" + (claims.size() == 1 ? "" : "s")
                + " and rated the overall message as " + overallRating + ".";

        return new FactCheckVerdict(
                overallRating,
                confidence,
                summary,
                claims,
                List.copyOf(sources)
        );
    }

    private FactCheckVerdict normalizeObjectVerdict(JsonNode root) {
        VerdictRating rating = toRating(textValue(root, "rating", "verdict"));
        double confidence = root.has("confidenceScore") && root.get("confidenceScore").isNumber()
                ? root.get("confidenceScore").doubleValue()
                : 0.0;
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        collectSources(root, sources);

        List<ClaimAnalysis> claims = new ArrayList<>();
        List<VerdictRating> claimRatings = new ArrayList<>();
        double claimConfidenceTotal = 0.0;
        int claimConfidenceCount = 0;
        JsonNode claimsNode = root.has("analyzedClaims") ? root.get("analyzedClaims") : root.get("claims");
        if (claimsNode != null && claimsNode.isArray()) {
            for (JsonNode node : claimsNode) {
                VerdictRating claimRating = toRating(textValue(node, "claimRating", "rating", "verdict"));
                claims.add(new ClaimAnalysis(
                        textValue(node, "claim"),
                        claimRating,
                        textValue(node, "explanation", "reason", "summary")
                ));
                claimRatings.add(claimRating);
                collectSources(node, sources);
                if (node.has("confidenceScore") && node.get("confidenceScore").isNumber()) {
                    claimConfidenceTotal += node.get("confidenceScore").doubleValue();
                    claimConfidenceCount++;
                }
            }
        }

        String claim = textValue(root, "claim");
        if (claims.isEmpty() && StringUtils.hasText(claim)) {
            claims.add(new ClaimAnalysis(
                    claim,
                    rating,
                    textValue(root, "explanation", "reason", "summary", "conciseSummary")
            ));
        }
        if (!root.has("rating") && !root.has("verdict") && !claimRatings.isEmpty()) {
            rating = aggregateRating(claimRatings);
        }
        if (confidence == 0.0 && claimConfidenceCount > 0) {
            confidence = claimConfidenceTotal / claimConfidenceCount;
        }

        String summary = textValue(root, "conciseSummary", "summary");
        if (!StringUtils.hasText(summary)) {
            summary = claims.isEmpty()
                    ? "Factify rated the overall message as " + rating + "."
                    : "Factify analyzed " + claims.size() + " claim" + (claims.size() == 1 ? "" : "s")
                    + " and rated the overall message as " + rating + ".";
        }

        return new FactCheckVerdict(rating, confidence, summary, claims, List.copyOf(sources));
    }

    private void collectSources(JsonNode node, LinkedHashSet<String> sources) {
        for (String fieldName : List.of("trustedSources", "sources")) {
            JsonNode sourceNode = node.get(fieldName);
            if (sourceNode == null || sourceNode.isNull()) {
                continue;
            }
            if (sourceNode.isArray()) {
                sourceNode.forEach(item -> {
                    if (item.isTextual() && StringUtils.hasText(item.asText())) {
                        sources.add(item.asText());
                    } else if (item.isObject()) {
                        String url = textValue(item, "url", "link");
                        if (StringUtils.hasText(url)) {
                            sources.add(url);
                        }
                    }
                });
            } else if (sourceNode.isTextual() && StringUtils.hasText(sourceNode.asText())) {
                sources.add(sourceNode.asText());
            }
        }
    }

    private VerdictRating aggregateRating(List<VerdictRating> ratings) {
        if (ratings.contains(VerdictRating.FALSE)) {
            return VerdictRating.FALSE;
        }
        if (ratings.contains(VerdictRating.MISLEADING)) {
            return VerdictRating.MISLEADING;
        }
        if (ratings.contains(VerdictRating.UNVERIFIED)) {
            return VerdictRating.UNVERIFIED;
        }
        return VerdictRating.TRUE;
    }

    private VerdictRating toRating(String value) {
        if (!StringUtils.hasText(value)) {
            return VerdictRating.UNVERIFIED;
        }
        try {
            return VerdictRating.valueOf(value.trim().toUpperCase().replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown verdict rating from model: {}", value);
            return VerdictRating.UNVERIFIED;
        }
    }

    private String textValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText();
            }
        }
        return "";
    }

    private String extractJsonPayload(String response) {
        if (!StringUtils.hasText(response)) {
            throw new DataRetrievalFailureException("Fact-check model returned an empty response.");
        }

        String cleaned = stripMarkdownFence(response.trim());
        int firstObjectChar = firstJsonStart(cleaned);
        if (firstObjectChar < 0) {
            throw new DataRetrievalFailureException("Fact-check model did not return JSON.");
        }

        int lastObjectChar = findBalancedJsonEnd(cleaned, firstObjectChar);
        if (lastObjectChar < 0) {
            log.error("Could not find balanced JSON payload in model response. responsePreview={}", preview(cleaned, 2000));
            throw new DataRetrievalFailureException("Fact-check model returned malformed JSON.");
        }

        return cleaned.substring(firstObjectChar, lastObjectChar + 1);
    }

    private String stripMarkdownFence(String value) {
        return Pattern.compile("^```(?:json)?\\s*|\\s*```$", Pattern.CASE_INSENSITIVE)
                .matcher(value)
                .replaceAll("")
                .trim();
    }

    private int firstJsonStart(String value) {
        int objectStart = value.indexOf('{');
        int arrayStart = value.indexOf('[');
        if (objectStart < 0) {
            return arrayStart;
        }
        if (arrayStart < 0) {
            return objectStart;
        }
        return Math.min(objectStart, arrayStart);
    }

    private int findBalancedJsonEnd(String value, int startIndex) {
        int objectDepth = 0;
        int arrayDepth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = startIndex; i < value.length(); i++) {
            char current = value.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = inString;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                objectDepth++;
            } else if (current == '}') {
                objectDepth--;
            } else if (current == '[') {
                arrayDepth++;
            } else if (current == ']') {
                arrayDepth--;
            }

            if (objectDepth < 0 || arrayDepth < 0) {
                return -1;
            }
            if (objectDepth == 0 && arrayDepth == 0) {
                return i;
            }
        }

        return -1;
    }

    private String preview(String value, int maxLength) {
        if (value == null) {
            return "<null>";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}
