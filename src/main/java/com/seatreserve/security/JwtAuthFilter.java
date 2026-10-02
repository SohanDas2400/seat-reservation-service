package com.seatreserve.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Parses a {@code Authorization: Bearer <jwt>} header and, if valid, publishes the user id into
 * {@link UserContext}. It does not reject unauthenticated requests here — endpoints that need a
 * user call {@link UserContext#requireUserId()} themselves (so public endpoints stay open).
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String userId = jwtService.verifyAndGetUserId(header.substring(7).trim());
                if (userId != null && !userId.isBlank()) {
                    UserContext.set(userId);
                }
            }
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}
