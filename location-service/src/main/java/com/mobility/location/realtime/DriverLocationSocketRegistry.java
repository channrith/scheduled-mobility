package com.mobility.location.realtime;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

import com.mobility.location.tracking.StoredDriverLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import tools.jackson.databind.json.JsonMapper;

@Component
public class DriverLocationSocketRegistry {

    private static final Logger log =
            LoggerFactory.getLogger(DriverLocationSocketRegistry.class);

    private final Map<UUID, Map<String, DriverConnection>> connections =
            new ConcurrentHashMap<>();

    private final JsonMapper jsonMapper;
    private final ThreadPoolTaskExecutor socketExecutor;

    public DriverLocationSocketRegistry(
            JsonMapper jsonMapper,
            @Qualifier("locationSocketExecutor")
            ThreadPoolTaskExecutor socketExecutor
    ) {
        this.jsonMapper = jsonMapper;
        this.socketExecutor = socketExecutor;
    }

    public void register(UUID driverId, WebSocketSession session) {
        Object expiry = session.getAttributes().get(
                DriverLocationHandshakeInterceptor.TOKEN_EXPIRES_AT
        );

        if (!(expiry instanceof Instant expiresAt)
                || !expiresAt.isAfter(Instant.now())) {
            closeQuietly(
                    session,
                    new CloseStatus(1008, "Authentication expired")
            );
            return;
        }

        DriverConnection connection =
                new DriverConnection(session, expiresAt);

        connections.compute(driverId, (id, driverConnections) -> {
            if (driverConnections == null) {
                driverConnections = new ConcurrentHashMap<>();
            }

            driverConnections.put(session.getId(), connection);
            return driverConnections;
        });
    }

    public void unregister(UUID driverId, String sessionId) {
        connections.computeIfPresent(driverId, (id, driverConnections) -> {
            driverConnections.remove(sessionId);

            return driverConnections.isEmpty() ? null : driverConnections;
        });
    }

    public int connectionCount(UUID driverId) {
        Map<String, DriverConnection> driverConnections =
                connections.get(driverId);

        return driverConnections == null
                ? 0
                : driverConnections.size();
    }

    public void sendToDriver(DriverLocationEvent event) {
        event.validate();

        Map<String, DriverConnection> driverConnections =
                connections.get(event.driverId());

        if (driverConnections == null || driverConnections.isEmpty()) {
            return;
        }

        String json = jsonMapper.writeValueAsString(event);

        for (DriverConnection connection : driverConnections.values()) {
            enqueue(
                    event.driverId(),
                    connection,
                    event.recordedAt(),
                    event.type(),
                    json
            );
        }
    }

    public void sendSnapshot(
            UUID driverId,
            String sessionId,
            StoredDriverLocation location
    ) {
        Map<String, DriverConnection> driverConnections =
                connections.get(driverId);

        if (driverConnections == null) {
            return;
        }

        DriverConnection connection = driverConnections.get(sessionId);

        if (connection == null) {
            return;
        }

        DriverLocationSnapshot snapshot =
                DriverLocationSnapshot.from(driverId, location);

        String json = jsonMapper.writeValueAsString(snapshot);

        enqueue(
                driverId,
                connection,
                snapshot.recordedAt(),
                snapshot.type(),
                json
        );
    }

    private void enqueue(
            UUID driverId,
            DriverConnection connection,
            Instant recordedAt,
            String messageType,
            String json
    ) {
        try {
            socketExecutor.execute(() ->
                    deliver(
                            driverId,
                            connection,
                            recordedAt,
                            messageType,
                            json
                    )
            );
        } catch (RejectedExecutionException exception) {
            log.warn(
                    "Location socket delivery overloaded: driverId={} sessionId={}",
                    driverId,
                    connection.session.getId()
            );

            removeAndClose(
                    driverId,
                    connection,
                    new CloseStatus(1013, "Reconnect later")
            );
        }
    }

    private void deliver(
            UUID driverId,
            DriverConnection connection,
            Instant recordedAt,
            String messageType,
            String json
    ) {
        synchronized (connection) {
            if (!connection.session.isOpen()) {
                unregister(driverId, connection.session.getId());
                return;
            }

            if (!connection.expiresAt.isAfter(Instant.now())) {
                removeAndClose(
                        driverId,
                        connection,
                        new CloseStatus(1008, "Authentication expired")
                );
                return;
            }

            // Keep snapshots and live updates in timestamp order.
            if (connection.lastSentAt != null
                    && !recordedAt.isAfter(connection.lastSentAt)) {
                return;
            }

            try {
                connection.session.sendMessage(new TextMessage(json));
                connection.lastSentAt = recordedAt;

                log.debug(
                        "Location message sent: type={} driverId={} sessionId={}",
                        messageType,
                        driverId,
                        connection.session.getId()
                );
            } catch (IOException | RuntimeException exception) {
                log.warn(
                        "Location socket delivery failed: sessionId={} errorType={}",
                        connection.session.getId(),
                        exception.getClass().getSimpleName()
                );

                removeAndClose(
                        driverId,
                        connection,
                        CloseStatus.SERVER_ERROR
                );
            }
        }
    }

    // This method belongs INSIDE DriverLocationSocketRegistry.
    @Scheduled(fixedDelay = 1_000)
    public void closeExpiredConnections() {
        Instant now = Instant.now();

        for (var driverEntry : connections.entrySet()) {
            UUID driverId = driverEntry.getKey();

            for (DriverConnection connection
                    : driverEntry.getValue().values()) {

                if (connection.expiresAt.isAfter(now)) {
                    continue;
                }

                synchronized (connection) {
                    if (!connection.session.isOpen()) {
                        unregister(
                                driverId,
                                connection.session.getId()
                        );
                        continue;
                    }

                    log.debug(
                            "Closing expired driver socket: driverId={} sessionId={}",
                            driverId,
                            connection.session.getId()
                    );

                    removeAndClose(
                            driverId,
                            connection,
                            new CloseStatus(1008, "Authentication expired")
                    );
                }
            }
        }
    }

    private void removeAndClose(
            UUID driverId,
            DriverConnection connection,
            CloseStatus status
    ) {
        unregister(driverId, connection.session.getId());
        closeQuietly(connection.session, status);
    }

    private static void closeQuietly(
            WebSocketSession session,
            CloseStatus status
    ) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException | RuntimeException ignored) {
            // The connection is already closing or unavailable.
        }
    }

    // This nested class also belongs INSIDE DriverLocationSocketRegistry.
    private static final class DriverConnection {

        private final WebSocketSession session;
        private final Instant expiresAt;
        private Instant lastSentAt;

        private DriverConnection(
                WebSocketSession session,
                Instant expiresAt
        ) {
            this.session = new ConcurrentWebSocketSessionDecorator(
                    session,
                    5_000,
                    64 * 1024
            );

            this.expiresAt = expiresAt;
        }
    }
}