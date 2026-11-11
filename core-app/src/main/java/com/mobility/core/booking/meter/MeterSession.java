package com.mobility.core.booking.meter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meter_sessions", schema = "booking")
class MeterSession {
    @Id
    @Column(name = "trip_id")
    UUID tripId;

    @Column(name = "last_at")
    Instant lastAt;

    @Column(name = "position_at")
    Instant positionAt;

    @Column(name = "first_at")
    Instant firstAt;

    @Column(name = "last_lat")
    Double lastLat;

    @Column(name = "last_lng")
    Double lastLng;

    @Column(name = "last_accuracy")
    Double lastAccuracy;

    @Column(name = "distance_meters", nullable = false)
    double distanceMeters;

    @Column(nullable = false)
    long samples;

    @Column(nullable = false)
    boolean finalized;

    @Version
    Long version;

    protected MeterSession() {}

    MeterSession(UUID tripId) {
        this.tripId = tripId;
    }
}
