package com.seatreserve.security;

import com.seatreserve.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/** Mints and verifies HS256 JWTs whose subject is the user id. */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds = 24 * 3600;

    public JwtService(AppProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String mint(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    /** Returns the user id from a valid token, or {@code null} if the token is missing/invalid. */
    public String verifyAndGetUserId(String token) {
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return jws.getPayload().getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
