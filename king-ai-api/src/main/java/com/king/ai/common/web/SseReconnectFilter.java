package com.king.ai.common.web;

import com.king.ai.common.Texts;
import com.king.ai.observability.ChatMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class SseReconnectFilter extends OncePerRequestFilter {

    private static final String LAST_EVENT_ID = "Last-Event-ID";
    private static final Set<String> PATHS = Set.of("/api/chat/send", "/api/public/chat/send");

    private final ChatMetrics chatMetrics;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!Texts.isBlank(request.getHeader(LAST_EVENT_ID))) {
            chatMetrics.sseReconnect();
        }
        filterChain.doFilter(request, response);
    }
}
