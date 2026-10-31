package com.mobility.location.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import com.mobility.platform.security.MobilityJwt;
import org.springframework.security.web.SecurityFilterChain;
import jakarta.servlet.DispatcherType;

@Configuration
public class LocationSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
                // For an API using bearer headers exclusively.
                .csrf(csrf -> csrf.disable())

                // Authenticate each request without an HTTP login session.
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // Define endpoint permissions.
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()

                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**"
                        ).permitAll()

                        .requestMatchers("/api/v1/drivers/me/**", "/api/v1/locations")
                        .hasRole("DRIVER")

                        .anyRequest()
                        .denyAll()
                )

                // Use the JwtDecoder bean and the role converter.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(
                                        MobilityJwt.authenticationConverter()
                                )
                        )
                );

        return http.build();
    }
}
