package com.mobility.location;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Token settings are {@code mobility.jwt.*}, handled by platform-security.
 *
 * @param positionTtl how long a driver's latest position and state are kept after their last ping
 * @param filter GPS noise filter
 */
@ConfigurationProperties("location")
public record LocationProperties(@DefaultValue("24h") Duration positionTtl, @DefaultValue Filter filter) {

	/**
	 * @param maxAccuracyMeters pings reported less accurate than this are dropped
	 * @param maxSpeedKmh a ping implying a faster move since the last stored position is dropped (GPS jump)
	 * @param speedCheckWindow the speed check only compares with a position at most this old, so one bad position
	 * that slipped through can't block a driver's pings for longer
	 */
	public record Filter(@DefaultValue("100") double maxAccuracyMeters, @DefaultValue("160") double maxSpeedKmh,
			@DefaultValue("2m") Duration speedCheckWindow) {
	}
}
