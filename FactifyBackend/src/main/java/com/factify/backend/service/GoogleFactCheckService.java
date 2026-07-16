package com.factify.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class GoogleFactCheckService {

    private static final Logger log = LoggerFactory.getLogger(GoogleFactCheckService.class);

    private static final String CLAIM_SEARCH_URL = "https://factchecktools.googleapis.com/v1alpha1/claims:search";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_QUERY_LENGTH = 500;
    private static final double MIN_QUERY_COVERAGE = 0.70;
    private static final double MIN_JACCARD_SIMILARITY = 0.22;
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "that", "this", "with", "from", "into", "onto", "about",
            "claim", "claims", "says", "said", "does", "did", "has", "have", "had",
            "is", "are", "was", "were", "will", "would", "can", "could", "should",
            "a", "an", "of", "in", "on", "to", "by", "as", "at", "or", "be",
            "omg", "wow", "guys", "look", "watch", "breaking", "urgent", "viral",
            "forward", "forwarded", "share", "shared", "please", "everyone", "today",
            "video", "photo", "image", "post", "message", "news", "real", "fake"
    );

    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final String apiKey;
    private final String languageCode;
    private final int pageSize;

    public GoogleFactCheckService(
            JsonMapper jsonMapper,
            @Value("${factify.google-fact-check.enabled:true}") boolean enabled,
            @Value("${factify.google-fact-check.api-key:}") String apiKey,
            @Value("${factify.google-fact-check.language-code:}") String languageCode,
            @Value("${factify.google-fact-check.page-size:5}") int pageSize
    ) {
        this.jsonMapper = jsonMapper;
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.languageCode = languageCode;
        this.pageSize = pageSize;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<FactCheckEvidence> search(String query) {
        if (!enabled) {
            log.debug("Google Fact Check lookup skipped because it is disabled.");
            return List.of();
        }
        if (!StringUtils.hasText(apiKey)) {
            log.warn("Google Fact Check lookup skipped because factify.google-fact-check.api-key is not configured.");
            return List.of();
        }
        if (!StringUtils.hasText(query)) {
            log.debug("Google Fact Check lookup skipped because query is blank.");
            return List.of();
        }

        String trimmedQuery = query.trim();
        List<String> queryVariants = buildQueryVariants(trimmedQuery);
        log.info(
                "Google Fact Check lookup started. queryLength={}, queryVariants={}, pageSize={}",
                trimmedQuery.length(),
                queryVariants.size(),
                pageSize
        );

        List<FactCheckEvidence> combinedEvidence = new ArrayList<>();
        for (String queryVariant : queryVariants) {
            List<FactCheckEvidence> evidence = searchSingleQuery(queryVariant);
            combinedEvidence.addAll(evidence);
        }

        List<FactCheckEvidence> deduplicatedEvidence = deduplicateByUrl(combinedEvidence);
        List<FactCheckEvidence> relevantEvidence = filterRelevantEvidence(trimmedQuery, deduplicatedEvidence);
        log.info(
                "Google Fact Check lookup completed. totalMatches={}, relevantMatches={}",
                deduplicatedEvidence.size(),
                relevantEvidence.size()
        );
        return relevantEvidence;
    }

    private List<FactCheckEvidence> searchSingleQuery(String query) {
        URI uri = buildSearchUri(query);
        log.debug("Google Fact Check request URL: {}", sanitizeApiKey(uri));

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .header("Accept", "application/json")
                .header("User-Agent", "Factify/1.0 google-fact-check-client")
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String contentType = response.headers().firstValue("content-type").orElse("<missing>");
            int bodyLength = response.body() == null ? 0 : response.body().length();
            log.debug(
                    "Google Fact Check raw response. status={}, contentType={}, bodyLength={}, queryPreview={}, bodyPreview={}",
                    response.statusCode(),
                    contentType,
                    bodyLength,
                    preview(query, 120),
                    preview(response.body(), 700)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn(
                        "Google Fact Check lookup failed. status={}, queryPreview={}, bodyPreview={}",
                        response.statusCode(),
                        preview(query, 120),
                        preview(response.body(), 500)
                );
                return List.of();
            }
            if (!StringUtils.hasText(response.body())) {
                log.info("Google Fact Check returned an empty body. queryPreview={}", preview(query, 120));
                return List.of();
            }

            List<FactCheckEvidence> evidence = parseEvidence(response.body());
            if (evidence.isEmpty()) {
                log.info(
                        "Google Fact Check returned no claim reviews. queryPreview={}, bodyPreview={}",
                        preview(query, 120),
                        preview(response.body(), 500)
                );
            } else {
                log.info("Google Fact Check query matched claim reviews. queryPreview={}, matches={}", preview(query, 120), evidence.size());
            }
            return evidence;
        } catch (IOException ex) {
            log.warn("Google Fact Check lookup failed due to IO error. queryPreview={}, message={}", preview(query, 120), ex.getMessage());
            return List.of();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Google Fact Check lookup interrupted. queryPreview={}", preview(query, 120));
            return List.of();
        } catch (RuntimeException ex) {
            log.warn("Google Fact Check lookup response could not be parsed. queryPreview={}", preview(query, 120), ex);
            return List.of();
        }
    }

    private URI buildSearchUri(String query) {
        StringBuilder builder = new StringBuilder(CLAIM_SEARCH_URL)
                .append("?query=")
                .append(urlEncode(query))
                .append("&pageSize=")
                .append(Math.max(1, Math.min(pageSize, 10)));

        if (StringUtils.hasText(languageCode)) {
            builder.append("&languageCode=").append(urlEncode(languageCode.trim()));
        }

        builder.append("&key=").append(urlEncode(apiKey.trim()));
        return URI.create(builder.toString());
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String sanitizeApiKey(URI uri) {
        return uri.toString().replaceAll("key=[^&]+", "key=<redacted>");
    }

    public String formatForPrompt(List<FactCheckEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return """
                    No matching Google Fact Check API claim reviews were found.
                    This means there are no verified fact-check source URLs available from Google Fact Check for this message.
                    Do not invent or guess trustedSources URLs. Return an empty trustedSources list unless the user's message itself includes a source URL.
                    """;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < evidence.size(); i++) {
            FactCheckEvidence item = evidence.get(i);
            builder.append(i + 1)
                    .append(". Claim: ")
                    .append(nullToEmpty(item.claimText()))
                    .append('\n')
                    .append("   Publisher: ")
                    .append(nullToEmpty(item.publisherName()))
                    .append('\n')
                    .append("   Claimant: ")
                    .append(nullToEmpty(item.claimant()))
                    .append('\n')
                    .append("   Rating: ")
                    .append(nullToEmpty(item.textualRating()))
                    .append('\n')
                    .append("   Title: ")
                    .append(nullToEmpty(item.title()))
                    .append('\n')
                    .append("   URL: ")
                    .append(nullToEmpty(item.url()))
                    .append('\n');
        }
        return builder.toString().trim();
    }

    private List<FactCheckEvidence> parseEvidence(String body) {
        JsonNode root = jsonMapper.readTree(body);
        JsonNode claims = root.get("claims");
        if (claims == null || !claims.isArray()) {
            return List.of();
        }

        List<FactCheckEvidence> evidence = new ArrayList<>();
        for (JsonNode claimNode : claims) {
            String claimText = textValue(claimNode, "text");
            String claimant = textValue(claimNode, "claimant");
            JsonNode reviews = claimNode.get("claimReview");
            if (reviews == null || !reviews.isArray()) {
                continue;
            }
            for (JsonNode reviewNode : reviews) {
                String url = textValue(reviewNode, "url");
                if (!StringUtils.hasText(url)) {
                    continue;
                }
                JsonNode publisher = reviewNode.get("publisher");
                evidence.add(new FactCheckEvidence(
                        claimText,
                        claimant,
                        publisher == null ? "" : textValue(publisher, "name"),
                        textValue(reviewNode, "title"),
                        textValue(reviewNode, "textualRating"),
                        url
                ));
            }
        }
        return evidence;
    }

    private List<String> buildQueryVariants(String query) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        addVariant(variants, query);

        String socialCleaned = stripSocialNoise(query);
        addVariant(variants, socialCleaned);

        String withoutPunctuation = socialCleaned.replaceAll("[\\p{Punct}&&[^'-]]+", " ");
        addVariant(variants, withoutPunctuation);

        String expandedAbbreviations = expandCommonAbbreviations(withoutPunctuation);
        addVariant(variants, expandedAbbreviations);

        String keywordQuery = extractKeywordQuery(withoutPunctuation);
        addVariant(variants, keywordQuery);

        String expandedKeywordQuery = extractKeywordQuery(expandedAbbreviations);
        addVariant(variants, expandedKeywordQuery);

        return variants.stream().limit(6).toList();
    }

    private String stripSocialNoise(String query) {
        return query
                .replaceAll("https?://\\S+", " ")
                .replaceAll("www\\.\\S+", " ")
                .replace('#', ' ')
                .replace('@', ' ')
                .replaceAll("(?i)\\b(omg|wow|guys|look|watch|breaking|urgent|viral|forwarded as received|please share|must watch)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String extractKeywordQuery(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>(meaningfulTokens(expandCommonAbbreviations(query)));
        return String.join(" ", tokens);
    }

    private String expandCommonAbbreviations(String query) {
        return query
                .replaceAll("(?i)\\bcm\\b", "chief minister")
                .replaceAll("(?i)\\bpm\\b", "prime minister")
                .replaceAll("(?i)\\bmla\\b", "member of legislative assembly")
                .replaceAll("(?i)\\bmp\\b", "member of parliament");
    }

    private void addVariant(Set<String> variants, String query) {
        if (!StringUtils.hasText(query)) {
            return;
        }
        String normalized = query.replaceAll("\\s+", " ").trim();
        if (normalized.length() > MAX_QUERY_LENGTH) {
            normalized = normalized.substring(0, MAX_QUERY_LENGTH).trim();
        }
        if (StringUtils.hasText(normalized)) {
            variants.add(normalized);
        }
    }

    private List<FactCheckEvidence> deduplicateByUrl(List<FactCheckEvidence> evidence) {
        LinkedHashSet<String> seenUrls = new LinkedHashSet<>();
        List<FactCheckEvidence> deduplicated = new ArrayList<>();
        for (FactCheckEvidence item : evidence) {
            if (StringUtils.hasText(item.url()) && seenUrls.add(item.url())) {
                deduplicated.add(item);
            }
        }
        return deduplicated;
    }

    private List<FactCheckEvidence> filterRelevantEvidence(String query, List<FactCheckEvidence> evidence) {
        if (evidence.isEmpty()) {
            return evidence;
        }

        Set<String> queryTokens = meaningfulTokens(extractKeywordQuery(query));
        if (queryTokens.isEmpty()) {
            queryTokens = meaningfulTokens(query);
        }
        if (queryTokens.isEmpty()) {
            return evidence;
        }

        List<FactCheckEvidence> relevant = new ArrayList<>();
        for (FactCheckEvidence item : evidence) {
            String evidenceText = String.join(
                    " ",
                    nullToEmpty(item.claimText()),
                    nullToEmpty(item.claimant()),
                    nullToEmpty(item.title()),
                    nullToEmpty(item.textualRating()),
                    nullToEmpty(item.publisherName())
            );
            Set<String> evidenceTokens = meaningfulTokens(evidenceText);
            int commonTokenCount = intersectionSize(queryTokens, evidenceTokens);
            int unionTokenCount = unionSize(queryTokens, evidenceTokens);
            double queryCoverage = commonTokenCount / (double) queryTokens.size();
            double jaccardSimilarity = unionTokenCount == 0 ? 0.0 : commonTokenCount / (double) unionTokenCount;

            if (queryCoverage >= MIN_QUERY_COVERAGE || jaccardSimilarity >= MIN_JACCARD_SIMILARITY) {
                relevant.add(item);
            } else {
                log.info(
                        "Dropping unrelated Google Fact Check evidence. queryCoverage={}, jaccard={}, queryPreview={}, evidenceClaimPreview={}, evidenceTitlePreview={}, url={}",
                        String.format(Locale.ROOT, "%.2f", queryCoverage),
                        String.format(Locale.ROOT, "%.2f", jaccardSimilarity),
                        preview(query, 120),
                        preview(item.claimText(), 120),
                        preview(item.title(), 120),
                        item.url()
                );
            }
        }
        return relevant;
    }

    private Set<String> meaningfulTokens(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() >= 3 && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private int intersectionSize(Set<String> first, Set<String> second) {
        int count = 0;
        for (String item : first) {
            if (second.contains(item)) {
                count++;
            }
        }
        return count;
    }

    private int unionSize(Set<String> first, Set<String> second) {
        LinkedHashSet<String> union = new LinkedHashSet<>(first);
        union.addAll(second);
        return union.size();
    }

    private String textValue(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String preview(String value, int maxLength) {
        if (value == null) {
            return "<null>";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    public record FactCheckEvidence(
            String claimText,
            String claimant,
            String publisherName,
            String title,
            String textualRating,
            String url
    ) {
    }
}
