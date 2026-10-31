package com.mobility.location.tracking;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;


public interface DriverLocationRepository {

    enum SaveResult {
        STORED,
        IGNORED,
        CONFLICT
    }

    /**
     * @return true when stored; false when an equal or newer
     *         measurement already exists.
     */
    boolean saveIfNewer(
            UUID driverId,
            UpdateDriverLocationRequest location,
            Instant receivedAt
    );

    Optional<StoredDriverLocation> findLatest(UUID driverId);

    default SaveResult compareAndSave(UUID driverId, UUID userId,
            UpdateDriverLocationRequest location, Instant receivedAt,
            Optional<StoredDriverLocation> expectedPrevious) {
        return compareAndSave(driverId, location, receivedAt, expectedPrevious);
    }

    SaveResult compareAndSave(
            UUID driverId,
            UpdateDriverLocationRequest location,
            Instant receivedAt,
            Optional<StoredDriverLocation> expectedPrevious
    );
}