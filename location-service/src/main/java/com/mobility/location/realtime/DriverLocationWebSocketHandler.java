package com.mobility.location.realtime;

import java.util.UUID;

import com.mobility.location.tracking.DriverLocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class DriverLocationWebSocketHandler
        extends TextWebSocketHandler {

    private static final Logger log =
            LoggerFactory.getLogger(DriverLocationWebSocketHandler.class);

    private final DriverLocationSocketRegistry registry;
    private final DriverLocationRepository repository;

    public DriverLocationWebSocketHandler(
            DriverLocationSocketRegistry registry,
            DriverLocationRepository repository
    ) {
        this.registry = registry;
        this.repository = repository;
    }

    @Override
    public void afterConnectionEstablished(
            WebSocketSession session
    ) throws Exception {
        UUID driverId = getDriverId(session);

        if (driverId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        registry.register(driverId, session);

        if (!session.isOpen()) {
            return;
        }

        log.debug(
                "Driver socket connected: driverId={} sessionId={} connections={}",
                driverId,
                session.getId(),
                registry.connectionCount(driverId)
        );

        try {
            repository.findLatest(driverId).ifPresent(location ->
                    registry.sendSnapshot(
                            driverId,
                            session.getId(),
                            location
                    )
            );
        } catch (DataAccessException exception) {
            log.warn(
                    "Location snapshot unavailable: driverId={} errorType={}",
                    driverId,
                    exception.getClass().getSimpleName()
            );

            registry.unregister(driverId, session.getId());
            session.close(
                    new CloseStatus(1011, "Location temporarily unavailable")
            );
        }
    }

    @Override
    public void afterConnectionClosed(
            WebSocketSession session,
            CloseStatus status
    ) {
        removeConnection(session);

        log.debug(
                "Driver socket disconnected: sessionId={} closeCode={}",
                session.getId(),
                status.getCode()
        );
    }

    @Override
    public void handleTransportError(
            WebSocketSession session,
            Throwable exception
    ) throws Exception {
        removeConnection(session);

        log.warn(
                "Driver socket transport error: sessionId={} errorType={}",
                session.getId(),
                exception.getClass().getSimpleName()
        );

        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(
            WebSocketSession session,
            TextMessage message
    ) throws Exception {
        // GPS uploads continue through the existing HTTP POST endpoint.
        session.close(
                new CloseStatus(1008, "Client messages are not supported")
        );
    }

    private void removeConnection(WebSocketSession session) {
        UUID driverId = getDriverId(session);

        if (driverId != null) {
            registry.unregister(driverId, session.getId());
        }
    }

    private UUID getDriverId(WebSocketSession session) {
        Object value = session.getAttributes().get(
                DriverLocationHandshakeInterceptor.DRIVER_ID
        );

        return value instanceof UUID driverId ? driverId : null;
    }
}