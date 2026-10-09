package com.settlementengine.core.lab;

import com.settlementengine.core.security.AccessTokenClaims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Records every request except the UI's own read-only polling of /lab (which would flood the index and the log ring),
 * and writes one INFO line per request so the OpenTelemetry agent's trace id lands in the logs next to the request id.
 */
@LabComponent
public class LabRequestIndexFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("com.settlementengine.core.lab.request");

    private final LabRequestIndex index;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public LabRequestIndexFilter(LabRequestIndex index, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.index = index;
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean labRead = path.startsWith("/lab") && "GET".equals(request.getMethod());
        return labRead || path.startsWith("/actuator") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Instant start = Instant.now();
        try {
            chain.doFilter(request, response);
        } finally {
            String requestId = MDC.get("requestId");
            if (requestId == null) {
                requestId = response.getHeader("X-Request-Id");
            }
            if (requestId != null) {
                UUID userId = null;
                String actor = null;
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof AccessTokenClaims claims) {
                    userId = claims.userId();
                    actor = username(userId);
                }
                index.add(new LabRequestIndex.Entry(requestId, request.getMethod(), request.getRequestURI(), response.getStatus(),
                        start, Instant.now(), userId, actor));
                log.info("HTTP {} {} -> {}", request.getMethod(), request.getRequestURI(), response.getStatus());
            }
        }
    }

    private String username(UUID userId) {
        try {
            return jdbc.queryForObject("select username from users where id = ?", String.class, userId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
