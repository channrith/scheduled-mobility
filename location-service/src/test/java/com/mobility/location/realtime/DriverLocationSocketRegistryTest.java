package com.mobility.location.realtime;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DriverLocationSocketRegistryTest {
    @Test
    void acceptedEventOnlyReachesItsOwnDriver() throws Exception {
        var executor = new ThreadPoolTaskExecutor();
        executor.initialize();
        try {
            var registry = new DriverLocationSocketRegistry(JsonMapper.builder().build(), executor);
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            WebSocketSession own = session("own");
            WebSocketSession other = session("other");
            registry.register(first, own);
            registry.register(second, other);
            Instant now = Instant.now();
            registry.sendToDriver(new DriverLocationEvent(1, "driver.location.updated", UUID.randomUUID(), first,
                    11.5, 104.9, 10.0, now, now));
            verify(own, timeout(2000)).sendMessage(any());
            verify(other, never()).sendMessage(any());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void revokedPassengerAccessClosesWithoutSendingLocation() throws Exception {
        var executor = new ThreadPoolTaskExecutor(); executor.initialize();
        try {
            var registry = new DriverLocationSocketRegistry(JsonMapper.builder().build(), executor);
            UUID driver = UUID.randomUUID();
            WebSocketSession passenger = session("passenger");
            var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
            when(passenger.getAttributes()).thenReturn(Map.of(
                DriverLocationHandshakeInterceptor.TOKEN_EXPIRES_AT, Instant.now().plusSeconds(60),
                PassengerLocationHandshakeInterceptor.DELIVERY_AUTHORIZATION, (java.util.function.BooleanSupplier) allowed::get));
            registry.register(driver, passenger);
            Instant now = Instant.now();
            registry.sendToDriver(new DriverLocationEvent(1, "driver.location.updated", UUID.randomUUID(), driver, 11.5, 104.9, 10.0, now, now));
            verify(passenger, timeout(2000)).sendMessage(any());
            clearInvocations(passenger); allowed.set(false);
            registry.sendToDriver(new DriverLocationEvent(1, "driver.location.updated", UUID.randomUUID(), driver, 11.6, 104.9, 10.0, now.plusSeconds(1), now.plusSeconds(1)));
            verify(passenger, timeout(2000)).close(argThat(status -> status.getCode() == 1008));
            verify(passenger, never()).sendMessage(any());
            org.junit.jupiter.api.Assertions.assertEquals(0, registry.connectionCount(driver));
        } finally { executor.shutdown(); }
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(
                DriverLocationHandshakeInterceptor.TOKEN_EXPIRES_AT, Instant.now().plusSeconds(60)));
        return session;
    }
}
