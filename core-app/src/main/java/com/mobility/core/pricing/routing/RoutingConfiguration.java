package com.mobility.core.pricing.routing;

import com.mobility.core.pricing.settings.PricingProperties;
import com.mobility.core.pricing.settings.PricingProperties.Routing;
import com.mobility.core.pricing.settings.PricingProperties.RoutingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class RoutingConfiguration {

	private static final Logger log = LoggerFactory.getLogger(RoutingConfiguration.class);

	@Bean
	RoutingClient routingClient(PricingProperties properties, Environment environment, JsonMapper json) {
		Routing routing = properties.routing();
		if (routing.provider() == RoutingProvider.OSRM) {
			if (routing.osrmUrl() == null || routing.osrmUrl().toString().isBlank()) {
				throw new IllegalStateException("pricing.routing.osrm-url (OSRM_URL) is required with the OSRM provider");
			}
			log.info("Routing with OSRM at {}", routing.osrmUrl());
			return new OsrmRoutingClient(routing.osrmUrl(), routing.timeout(), json);
		}
		if (environment.matchesProfiles("prod | production")) {
			throw new IllegalStateException(
					"pricing.routing.provider=STRAIGHT_LINE is an estimate for tests; production quotes need OSRM");
		}
		log.warn("Routing with straight-line estimates (detour {} at {} km/h): tests and local runs only",
				routing.detourFactor(), routing.averageSpeedKmh());
		return new StraightLineRoutingClient(routing.detourFactor(), routing.averageSpeedKmh());
	}
}
