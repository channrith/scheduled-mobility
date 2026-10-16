package com.mobility.location.security;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
public class AuthCheckController {

    @GetMapping("/api/v1/drivers/me/auth-check")
    public Map<String, String> check(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return Map.of(
                "status", "authenticated",
                "userId", jwt.getSubject()
        );
    }
}