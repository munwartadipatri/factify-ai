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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Retrieves web evidence before querying the Google Fact Check API. */
@Service
public class TavilySearchService {

    private static final Logger log = LoggerFactory.getLogger(TavilySearchService.class);
    private static final URI SEARCH_URI = URI.create("https://api.tavily.com/search");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final String apiKey;
    private final String searchDepth;
    private final int maxResults;

    public TavilySearchService(
            JsonMapper jsonMapper,
            @Value("${factify.tavily.enabled:true}") boolean enabled,
            @Value("${factify.tavily.api-key:}") String apiKey,
            @Value("${factify.tavily.search-depth:advanced}") String searchDepth,
            @Value("${factify.tavily.max-results:5}") int maxResults
    ) {
        this.jsonMapper = jsonMapper;
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.searchDepth = searchDepth;
        this.maxResults = maxResults;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<SearchEvidence> search(String query) {
        if (!enabled || !StringUtils.hasText(apiKey) || !StringUtils.hasText(query)) {
            if (enabled && !StringUtils.hasText(apiKey)) {
                log.warn("Tavily lookup skipped because factify.tavily.api-key is not configured.");
            }
            return List.of();
        }

        try {
            String requestBody = jsonMapper.writeValueAsString(Map.of(
                    "query", query.trim(),
                    "search_depth", normalizedSearchDepth(),
                    "max_results", Math.max(1, Math.min(maxResults, 10)),
                    "include_answer", false,
                    "include_raw_content", false
            ));
            HttpRequest request = HttpRequest.newBuilder(SEARCH_URI)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("User-Agent", "Factify/1.0 tavily-client")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            log.info("Tavily search started. queryLength={}, maxResults={}", query.length(), maxResults);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Tavily search failed. status={}, queryPreview={}", response.statusCode(), preview(query, 120));
                return List.of();
            }

            List<SearchEvidence> evidence = parseEvidence(response.body());
            log.info("Tavily search completed. results={}", evidence.size());
            return evidence;
        } catch (IOException ex) {
            log.warn("Tavily search failed due to IO error. queryPreview={}, message={}", preview(query, 120), ex.getMessage());
            return List.of();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Tavily search interrupted. queryPreview={}", preview(query, 120));
            return List.of();
        } catch (RuntimeException ex) {
            log.warn("Tavily response could not be processed. queryPreview={}", preview(query, 120), ex);
            return List.of();
        }
    }

    /**
     * Produces concise claim-query candidates from the best Tavily results for Google Fact Check.
     * The original user message always remains the primary query.
     */
    public List<String> buildFactCheckQueries(List<SearchEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (SearchEvidence result : evidence) {
            addQuery(queries, result.title());
            addQuery(queries, firstSentence(result.content()));
            if (queries.size() >= 2) {
                break;
            }
        }
        return List.copyOf(queries);
    }

    public String formatForPrompt(List<SearchEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "No Tavily web-search evidence was available.";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < evidence.size(); i++) {
            SearchEvidence item = evidence.get(i);
            builder.append(i + 1).append(". Title: ").append(nullToEmpty(item.title())).append('\n')
                    .append("   URL: ").append(nullToEmpty(item.url())).append('\n')
                    .append("   Snippet: ").append(nullToEmpty(item.content())).append('\n');
        }
        return builder.toString().trim();
    }

    private List<SearchEvidence> parseEvidence(String body) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        JsonNode results = jsonMapper.readTree(body).get("results");
        if (results == null || !results.isArray()) {
            return List.of();
        }
        List<SearchEvidence> evidence = new ArrayList<>();
        for (JsonNode result : results) {
            String url = textValue(result, "url");
            if (StringUtils.hasText(url)) {
                evidence.add(new SearchEvidence(textValue(result, "title"), url, textValue(result, "content")));
            }
        }
        return evidence;
    }

    private void addQuery(LinkedHashSet<String> queries, String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return;
        }
        String normalized = candidate.replaceAll("\\s+", " ").trim();
        if (normalized.length() >= 12) {
            queries.add(normalized.length() > 300 ? normalized.substring(0, 300).trim() : normalized);
        }
    }

    private String firstSentence(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        int sentenceEnd = normalized.indexOf(". ");
        return sentenceEnd >= 0 ? normalized.substring(0, sentenceEnd + 1) : normalized;
    }

    private String normalizedSearchDepth() {
        return "basic".equalsIgnoreCase(searchDepth) ? "basic" : "advanced";
    }

    private String textValue(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String preview(String value, int maxLength) {
        String normalized = value == null ? "<null>" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    public record SearchEvidence(String title, String url, String content) {
    }
}
