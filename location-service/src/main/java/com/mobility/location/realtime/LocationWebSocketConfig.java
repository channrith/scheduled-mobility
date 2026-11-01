package com.mobility.location.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class LocationWebSocketConfig implements WebSocketConfigurer {

    private final DriverLocationWebSocketHandler handler;
    private final DriverLocationHandshakeInterceptor interceptor;
    private final PassengerLocationHandshakeInterceptor passengerInterceptor;

    public LocationWebSocketConfig(
            DriverLocationWebSocketHandler handler,
            DriverLocationHandshakeInterceptor interceptor,
            PassengerLocationHandshakeInterceptor passengerInterceptor
    ) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.passengerInterceptor = passengerInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(
            WebSocketHandlerRegistry registry
    ) {
        registry.addHandler(
                handler,
                "/api/v1/drivers/me/location/live"
        ).addInterceptors(interceptor);
        registry.addHandler(handler, "/api/v1/passenger/trips/{tripId}/location/live").addInterceptors(passengerInterceptor);
    }
}