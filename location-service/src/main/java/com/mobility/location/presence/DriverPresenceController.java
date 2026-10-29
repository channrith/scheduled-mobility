package com.mobility.location.presence;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/drivers/me/presence")
public class DriverPresenceController {

    private final DriverPresenceService service;

    public DriverPresenceController(DriverPresenceService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<DriverPresenceResponse> get(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.get(jwt));
    }
}
