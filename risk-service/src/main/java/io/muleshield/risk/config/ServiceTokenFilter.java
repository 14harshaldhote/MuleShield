package io.muleshield.risk.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Only the bank's own services may ask for a decision: the API answers "would this payment be
 * stopped, and why", which is exactly what a fraudster would like to probe. Compared in constant
 * time. In production this is mutual TLS inside the service mesh.        [OWASP API2:2023]
 */
@Component
class ServiceTokenFilter extends OncePerRequestFilter {

    private final byte[] expected;

    ServiceTokenFilter(MuleShieldProperties props) {
        if (props.serviceToken() == null || props.serviceToken().length() < 24) {
            throw new IllegalStateException("muleshield.service-token must be set (24+ characters)");
        }
        this.expected = ("Bearer " + props.serviceToken()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
