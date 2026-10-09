package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlementengine.core.api.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/** Kill switch and per-IP token bucket in front of every mutating {@code /lab/**} call. */
@LabComponent
public class LabRequestGuardFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final LabProperties props;
    private final ObjectMapper objectMapper;
    private final TokenBucketRateLimiter limiter;

    public LabRequestGuardFilter(LabProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.limiter = new TokenBucketRateLimiter(props.requestsPerMinutePerIp(), Duration.ofMinutes(1));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/lab") || path.startsWith("/lab/")) || !MUTATING.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (props.readOnly()) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "The lab is in read-only mode: actions that change data are switched off.");
            return;
        }
        String ip = ClientIp.of(request, props.trustForwardedFor());
        if (!limiter.tryAcquire(ip)) {
            response.setHeader("Retry-After", "10");
            reject(response, HttpStatus.TOO_MANY_REQUESTS,
                    "Too many lab actions from this address; limit is " + props.requestsPerMinutePerIp() + " per minute.");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ErrorResponse.of(status.value(), status.getReasonPhrase(), message));
    }
}
