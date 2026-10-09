package com.settlementengine.core.fraud;

import com.settlementengine.core.invoicing.FraudDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Fraud detection is advisory, not load-bearing: if the ML service is unreachable or slow, we
 * fail open (allow financing to proceed) rather than let an optional dependency block the core
 * financing flow. This also means Java tests that don't run the Python service naturally fail
 * open without needing a special "disabled" flag.
 */
@Component
public class HttpFraudDetectionClient implements FraudDetectionClient {

    private static final Logger log = LoggerFactory.getLogger(HttpFraudDetectionClient.class);

    private final RestClient restClient;

    public HttpFraudDetectionClient(
            @Value("${settlement-engine.fraud-detection.base-url:http://localhost:8000}") String baseUrl,
            @Value("${settlement-engine.fraud-detection.timeout-ms:2000}") long timeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public FraudCheckResult check(FraudCheckRequest request) {
        try {
            ScoreResponse response = restClient.post()
                    .uri("/score")
                    .body(request)
                    .retrieve()
                    .body(ScoreResponse.class);

            if (response == null) {
                return FraudCheckResult.allowNoSignal();
            }
            return new FraudCheckResult(response.score(), response.decision(), response.reasons());
        } catch (RestClientException e) {
            log.warn("Fraud detection service unavailable, failing open (allow): {}", e.getMessage());
            return FraudCheckResult.allowNoSignal();
        }
    }

    private record ScoreResponse(BigDecimal score, FraudDecision decision, List<String> reasons) {
    }
}
