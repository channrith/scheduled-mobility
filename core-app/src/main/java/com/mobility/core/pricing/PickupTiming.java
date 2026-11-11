package com.mobility.core.pricing;

/** A quote records whether the caller omitted pickupAt; bookings cannot reinterpret its timing. */
public enum PickupTiming {
    NOW,
    SCHEDULED
}
