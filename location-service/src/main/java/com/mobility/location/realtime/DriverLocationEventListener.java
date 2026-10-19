package com.mobility.location.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

@Component
public class DriverLocationEventListener implements MessageListener {

    private static final Logger log =
            LoggerFactory.getLogger(DriverLocationEventListener.class);

    private static final int MAX_EVENT_BYTES = 8 * 1024;

    private final JsonMapper jsonMapper;
    private final DriverLocationEventRouter router;

    public DriverLocationEventListener(
            JsonMapper jsonMapper,
            DriverLocationEventRouter router
    ) {
        this.jsonMapper = jsonMapper;
        this.router = router;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        byte[] body = message.getBody();

        if (body.length == 0 || body.length > MAX_EVENT_BYTES) {
            log.warn(
                    "Location event discarded: invalid payload size bytes={}",
                    body.length
            );
            return;
        }

        DriverLocationEvent event;

        try {
            event = jsonMapper.readValue(
                    body,
                    DriverLocationEvent.class
            );

            if (event == null) {
                throw new IllegalArgumentException("Empty event");
            }

            event.validate();
        } catch (Exception exception) {
            log.warn(
                    "Location event discarded: invalid payload errorType={}",
                    exception.getClass().getSimpleName()
            );
            return;
        }

        try {
            router.route(event);
        } catch (RuntimeException exception) {
            log.error(
                    "Location event routing failed: eventId={} driverId={} errorType={}",
                    event.eventId(),
                    event.driverId(),
                    exception.getClass().getSimpleName()
            );
        }
    }
}