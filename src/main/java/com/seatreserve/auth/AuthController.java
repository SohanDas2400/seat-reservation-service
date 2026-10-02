package com.seatreserve.auth;

import com.seatreserve.security.JwtService;
import com.seatreserve.web.BadRequestException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev helper to mint a user JWT. In production this is replaced by a real identity provider;
 * here it lets a load test create as many distinct user tokens as it needs.
 */
@RestController
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @PostMapping("/auth/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        if (request.user_id() == null || request.user_id().isBlank()) {
            throw new BadRequestException("user_id is required");
        }
        return new TokenResponse(jwtService.mint(request.user_id()), request.user_id(), "Bearer");
    }

    public record TokenRequest(@NotBlank String user_id) {
    }

    public record TokenResponse(String token, String user_id, String token_type) {
    }
}
