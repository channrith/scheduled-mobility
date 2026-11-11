package com.mobility.core.booking.meter;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

interface MeterSessionRepository extends JpaRepository<MeterSession, UUID> {
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MeterSession> findById(UUID id);
}
