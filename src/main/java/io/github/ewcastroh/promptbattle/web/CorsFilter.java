package io.github.ewcastroh.promptbattle.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CORS for {@code CORS_ALLOWED_ORIGINS} (API.md §3). Listed origins get the CORS headers; other origins
 * get none (the browser blocks them) and their preflights are refused with 403. No credentials.
 */
public final class CorsFilter extends OncePerRequestFilter {

    static final String METHODS = "GET, POST, PUT, DELETE";
    static final String HEADERS = "Authorization, X-Admin-Token, Content-Type, If-None-Match";
    static final String EXPOSED = "ETag, Date, Retry-After, Content-Disposition";
    static final String MAX_AGE = "600";
    private static final Set<String> METHOD_SET = Set.of("GET", "POST", "PUT", "DELETE", "HEAD");
    private static final Set<String> HEADER_SET = Arrays.stream(HEADERS.split(","))
            .map(h -> h.strip().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());

    private final Set<String> allowedOrigins;

    public CorsFilter(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins.stream().map(String::strip).filter(o -> !o.isEmpty())
                .map(o -> o.endsWith("/") ? o.substring(0, o.length() - 1) : o).collect(Collectors.toSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null) {
            chain.doFilter(request, response);
            return;
        }
        response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        boolean preflight = "OPTIONS".equalsIgnoreCase(request.getMethod())
                && request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null;
        boolean allowed = allowedOrigins.contains(origin);
        if (preflight) {
            response.addHeader(HttpHeaders.VARY, HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
            response.addHeader(HttpHeaders.VARY, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
            if (!allowed || !preflightAcceptable(request)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, METHODS);
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, HEADERS);
            response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, MAX_AGE);
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        if (allowed) {
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, EXPOSED);
        }
        chain.doFilter(request, response);
    }

    private static boolean preflightAcceptable(HttpServletRequest request) {
        String method = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD).strip().toUpperCase(Locale.ROOT);
        if (!METHOD_SET.contains(method)) {
            return false;
        }
        String headers = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
        if (headers == null || headers.isBlank()) {
            return true;
        }
        for (String h : headers.split(",")) {
            String name = h.strip().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !HEADER_SET.contains(name)) {
                return false;
            }
        }
        return true;
    }
}
