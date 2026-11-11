-- Only newly created quotes can request immediate service. Previously all bookings were scheduled.
ALTER TABLE pricing.fare_quotes ADD COLUMN pickup_timing VARCHAR(12) NOT NULL DEFAULT 'SCHEDULED'
    CHECK (pickup_timing IN ('NOW','SCHEDULED'));
