package com.mobility.core.dispatch.automatic;

import java.time.Duration;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("dispatch.automatic")
public record AutomaticAssignmentProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("20") @Min(1) int batchSize,
        @DefaultValue("60") @Min(1) int maxAttempts,
        @DefaultValue("1m") Duration retryDelay,
        @DefaultValue("7d") Duration horizon) {
    public AutomaticAssignmentProperties {
        if (retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()
                || horizon == null || horizon.isNegative() || horizon.isZero()) {
            throw new IllegalArgumentException("Automatic dispatch durations must be positive");
        }
    }
}
