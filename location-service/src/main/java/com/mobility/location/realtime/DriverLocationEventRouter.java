package com.mobility.location.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DriverLocationEventRouter {

    private static final Logger log =
            LoggerFactory.getLogger(DriverLocationEventRouter.class);

    private final DriverLocationSocketRegistry socketRegistry;

    public DriverLocationEventRouter(
            DriverLocationSocketRegistry socketRegistry
    ) {
        this.socketRegistry = socketRegistry;
    }

    public void route(DriverLocationEvent event) {
        log.debug(
                "Location event received: eventId={} driverId={} recordedAt={}",
                event.eventId(),
                event.driverId(),
                event.recordedAt()
        );

        socketRegistry.sendToDriver(event);
    }
}