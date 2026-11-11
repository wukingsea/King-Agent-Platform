package com.king.ai.common.web;

import com.king.ai.config.KingAiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards non-health actuator endpoints (prometheus, info, metrics, ...). Health probes stay
 * public so container healthchecks keep working. When {@code king-ai.management.token} is
 * configured, callers must present it via {@code X-Actuator-Token} or
 * {@code Authorization: Bearer <token>}; when unset the endpoints remain open for backwards
 * compatibility and {@link com.king.ai.config.StartupSecurityChecks} warns in production.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@RequiredArgsConstructor
public class ActuatorAccessFilter extends OncePerRequestFilter {

    private static final String ACTUATOR_PREFIX = "/actuator";
    private static final String HEALTH_PREFIX = "/actuator/health";
    private static final String TOKEN_HEADER = "X-Actuator-Token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final KingAiProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.equals(ACTUATOR_PREFIX) && !path.startsWith(ACTUATOR_PREFIX + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals(HEALTH_PREFIX) || path.startsWith(HEALTH_PREFIX + "/")) {
            filterChain.doFilter(request, response);
            return;
        }
        String expected = properties.getManagement().getToken();
        if (expected == null || expected.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided == null || provided.isBlank()) {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                provided = authorization.substring(BEARER_PREFIX.length()).trim();
            }
        }
        if (provided == null || provided.isBlank()
                || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                        expected.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Actuator endpoint requires a valid token");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
