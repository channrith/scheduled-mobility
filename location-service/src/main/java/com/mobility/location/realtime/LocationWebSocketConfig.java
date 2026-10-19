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

    public LocationWebSocketConfig(
            DriverLocationWebSocketHandler handler,
            DriverLocationHandshakeInterceptor interceptor
    ) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(
            WebSocketHandlerRegistry registry
    ) {
        registry.addHandler(
                handler,
                "/api/v1/drivers/me/location/live"
        ).addInterceptors(interceptor);
    }
}