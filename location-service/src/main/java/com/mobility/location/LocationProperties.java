package com.mobility.location;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Token settings are {@code mobility.jwt.*}, handled by platform-security. */
@ConfigurationProperties("location")
public record LocationProperties(@DefaultValue("24h") Duration positionTtl) {
}
