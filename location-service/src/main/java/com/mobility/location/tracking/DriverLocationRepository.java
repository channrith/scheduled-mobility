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

    SaveResult compareAndSave(
            UUID driverId,
            UpdateDriverLocationRequest location,
            Instant receivedAt,
            Optional<StoredDriverLocation> expectedPrevious
    );
}