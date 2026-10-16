package com.mobility.location.tracking;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequestMapping("/api/v1/drivers/me/location")
public class DriverLocationController {

    private final DriverLocationService service;

    public DriverLocationController(DriverLocationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Void> update(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UpdateDriverLocationRequest request
    ) {
        service.update(jwt, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public DriverLocationResponse getLatest(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.getLatest(jwt);
    }
}