package com.enterprise.mm.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final int rpm;
    private final ApiSecurityErrorHandler errorHandler;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger requestsSinceCleanup = new AtomicInteger();

    public RateLimitFilter(@Value("${app.rate-limit.requests-per-minute}") int rpm, ApiSecurityErrorHandler errorHandler) {
        this.rpm = rpm;
        this.errorHandler = errorHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (rpm <= 0) {
            filterChain.doFilter(request, response);
            return;
        }
        cleanupExpiredBuckets();
        Instant now = Instant.now();
        String key = request.getRemoteAddr() + ":" + request.getRequestURI();
        Bucket bucket = buckets.compute(key, (ignored, old) -> old == null || old.expired(now) ? new Bucket(now) : old);
        if (bucket.count.incrementAndGet() > rpm) {
            errorHandler.writeError(response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded", "RateLimitExceeded");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void cleanupExpiredBuckets() {
        int cleanupEvery = Math.max(1, rpm);
        if (requestsSinceCleanup.incrementAndGet() % cleanupEvery == 0) {
            Instant now = Instant.now();
            buckets.entrySet().removeIf(entry -> entry.getValue().expired(now));
        }
    }

    private static class Bucket {
        private final Instant startedAt;
        private final AtomicInteger count = new AtomicInteger();

        private Bucket(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private boolean expired(Instant now) {
            return startedAt.plusSeconds(60).isBefore(now);
        }
    }
}