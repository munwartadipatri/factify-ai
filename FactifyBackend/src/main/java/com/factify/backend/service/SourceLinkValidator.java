package com.factify.backend.service;

import com.factify.backend.domain.model.FactCheckVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class SourceLinkValidator {

    private static final Logger log = LoggerFactory.getLogger(SourceLinkValidator.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_VALIDATED_SOURCES = 8;

    private final HttpClient httpClient;

    public SourceLinkValidator() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public FactCheckVerdict validate(FactCheckVerdict verdict) {
        if (verdict == null || verdict.trustedSources().isEmpty()) {
            return verdict;
        }

        List<String> validSources = verdict.trustedSources().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(LinkedHashSet<String>::new, LinkedHashSet::add, LinkedHashSet::addAll)
                .stream()
                .limit(MAX_VALIDATED_SOURCES)
                .filter(this::isReachableHttpsUrl)
                .toList();

        int removedCount = verdict.trustedSources().size() - validSources.size();
        if (removedCount > 0) {
            log.warn(
                    "Removed invalid or unreachable trusted source links. originalCount={}, validCount={}, removedCount={}",
                    verdict.trustedSources().size(),
                    validSources.size(),
                    removedCount
            );
        }

        return new FactCheckVerdict(
                verdict.rating(),
                verdict.confidenceScore(),
                verdict.conciseSummary(),
                verdict.analyzedClaims(),
                validSources
        );
    }

    private boolean isReachableHttpsUrl(String value) {
        URI uri = parseHttpsUri(value);
        if (uri == null) {
            log.warn("Dropping trusted source because it is not a valid HTTPS URL. url={}", value);
            return false;
        }

        int status = requestStatus(uri, "HEAD");
        if (status == 405 || status <= 0) {
            status = requestStatus(uri, "GET");
        }

        if (status >= 200 && status < 400) {
            log.debug("Trusted source validated. status={}, url={}", status, uri);
            return true;
        }
        if (status == 401 || status == 403 || status == 429) {
            log.info("Keeping trusted source with protected or rate-limited response. status={}, url={}", status, uri);
            return true;
        }
        if (status == 404 || status == 410) {
            log.warn("Dropping dead trusted source. status={}, url={}", status, uri);
            return false;
        }
        log.warn("Dropping unreachable trusted source. status={}, url={}", status, uri);
        return false;
    }

    private URI parseHttpsUri(String value) {
        try {
            URI uri = new URI(value.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                return null;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".local")) {
                return null;
            }
            return uri;
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private int requestStatus(URI uri, String method) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", "Factify/1.0 source-link-validator")
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException ex) {
            log.debug("Trusted source validation request failed. method={}, url={}, message={}", method, uri, ex.getMessage());
            return -1;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.debug("Trusted source validation request interrupted. method={}, url={}", method, uri);
            return -1;
        } catch (IllegalArgumentException ex) {
            log.debug("Trusted source validation request rejected. method={}, url={}, message={}", method, uri, ex.getMessage());
            return -1;
        }
    }
}
