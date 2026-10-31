package com.mobility.location.ping;

import com.mobility.location.tracking.DriverLocationService;
import com.mobility.location.tracking.UpdateDriverLocationRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Compatibility entry point from main; all GPS writes use the validated atomic save. */
@RestController
@RequestMapping("/api/v1/locations")
class LocationController {
    private final DriverLocationService locations;

    LocationController(DriverLocationService locations) {
        this.locations = locations;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> ping(@AuthenticationPrincipal Jwt jwt,
            @RequestBody UpdateDriverLocationRequest request) {
        return ResponseEntity.accepted().body(Map.of("stored", locations.update(jwt, request)));
    }
}
