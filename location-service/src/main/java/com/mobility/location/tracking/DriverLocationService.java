package com.mobility.location.tracking;

import java.time.Duration;
import java.time.Instant;

import com.mobility.location.driver.DriverAccessResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DriverLocationService {

    private final DriverAccessResolver driverResolver;
    private final LocationValidator validator;
    private final DriverLocationRepository repository;
    private final LocationJumpValidator jumpValidator;


    public DriverLocationService(
            DriverAccessResolver driverResolver,
            LocationValidator validator,
            DriverLocationRepository repository,
            LocationJumpValidator jumpValidator
    ) {
        this.driverResolver = driverResolver;
        this.validator = validator;
        this.repository = repository;
        this.jumpValidator = jumpValidator;
    }

    public boolean update(
            Jwt jwt,
            UpdateDriverLocationRequest request
    ) {
        validator.validate(request, Instant.now());

        var driver = driverResolver.resolve(jwt);

        if (!"APPROVED".equals(driver.status())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Driver must be approved before uploading location"
            );
        }

        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                Instant receivedAt = Instant.now();

                // Recheck age after core lookup or a concurrent-update retry.
                validator.validate(request, receivedAt);

                var previous = repository.findLatest(driver.driverId());

                if (previous.isPresent()) {
                    var lastLocation = previous.get();

                    if (!request.recordedAt()
                            .isAfter(lastLocation.recordedAt())) {
                        return false;
                    }

                    jumpValidator.validate(lastLocation, request);
                }

                var result = repository.compareAndSave(
                        driver.driverId(),
                        driver.userId(),
                        request,
                        receivedAt,
                        previous
                );

                if (result != DriverLocationRepository.SaveResult.CONFLICT) {
                    return result == DriverLocationRepository.SaveResult.STORED;
                }
            }

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Location changed concurrently; retry the update"
            );

        } catch (DataAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Location storage is temporarily unavailable"
            );
        }
    }

    public DriverLocationResponse getLatest(Jwt jwt) {
        var driver = driverResolver.resolve(jwt);

        if (!"APPROVED".equals(driver.status())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Driver must be approved before reading location"
            );
        }

        StoredDriverLocation location;

        try {
            location = repository.findLatest(driver.driverId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "No stored driver location"
                    ));
        } catch (DataAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Location storage is temporarily unavailable"
            );
        }

        Instant now = Instant.now();

        Duration age = Duration.between(location.recordedAt(), now);

        // A small future timestamp is possible within our clock tolerance.
        if (age.isNegative()) {
            age = Duration.ZERO;
        }

        // Initial freshness policy; make configurable before staging.
        boolean fresh = age.compareTo(Duration.ofSeconds(30)) <= 0;

        return new DriverLocationResponse(
                location.latitude(),
                location.longitude(),
                location.accuracyMeters(),
                location.recordedAt(),
                location.receivedAt(),
                age.getSeconds(),
                fresh
        );
    }
}