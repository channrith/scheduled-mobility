-- The quote's driving time, kept with the booking so dispatch can plan when a driver is free again.
ALTER TABLE booking.ride_requests ADD COLUMN duration_seconds BIGINT CHECK (duration_seconds >= 0);
