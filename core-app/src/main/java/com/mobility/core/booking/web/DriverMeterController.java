package com.mobility.core.booking.web;

import com.mobility.core.booking.meter.MeterService;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.shared.web.ApiException;

import io.swagger.v3.oas.annotations.Operation;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/driver/meter")
@PreAuthorize("hasRole('DRIVER')")
class DriverMeterController {
    private final MeterService meter;
    private final CurrentUserProvider users;

    DriverMeterController(MeterService meter, CurrentUserProvider users) {
        this.meter = meter;
        this.users = users;
    }

    @PostMapping("/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Submit a measured GPS sample to the driver's active meter",
            description =
                    "Requires Idempotency-Key. No active meter: no-op. Server validates timestamp,"
                        + " accuracy, continuity and speed; computes distance. The fare is"
                        + " finalized atomically with trip completion. Coverage gaps prevent"
                        + " completion and require operations assistance.")
    void sample(
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody MeterService.Sample sample) {
        if (key == null || key.isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "booking.idempotency-key-required");
        meter.accept(users.require().userId(), sample);
    }
}
