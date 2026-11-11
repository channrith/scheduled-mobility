package com.mobility.core.dispatch.automatic;

import java.util.List;
import java.util.UUID;
import com.mobility.core.booking.BookingEvents.RideBooked;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RideBookedListener {
    private final JsonMapper json;
    private final AssignmentJobs jobs;

    RideBookedListener(JsonMapper json, AssignmentJobs jobs) {
        this.json = json;
        this.jobs = jobs;
    }

    @RabbitListener(queues = AutomaticAssignmentConfiguration.QUEUE,
            containerFactory = "automaticAssignmentListenerFactory",
            autoStartup = "${dispatch.automatic.listener-enabled:true}")
    public void receive(Message message) {
        UUID eventId;
        RideBooked booked;
        try {
            var envelope = json.readTree(message.getBody());
            if (!"RideBooked".equals(envelope.path("type").asString())
                    || !"ride".equals(envelope.path("aggregateType").asString())) {
                throw new IllegalArgumentException("Unexpected event type");
            }
            eventId = UUID.fromString(envelope.path("eventId").asString());
            booked = json.treeToValue(envelope.path("payload"), RideBooked.class);
            if (booked == null || booked.rideId() == null || booked.tripIds() == null
                    || booked.tripIds().isEmpty() || booked.tripIds().stream().anyMatch(java.util.Objects::isNull)
                    || !booked.rideId().toString().equals(envelope.path("aggregateId").asString())) {
                throw new IllegalArgumentException("Invalid ride event");
            }
        } catch (RuntimeException invalid) {
            // Poison messages go to the durable DLQ; never loop on malformed input.
            throw new AmqpRejectAndDontRequeueException("Invalid ride-booked event", invalid);
        }
        // Acknowledge only after this transactional call commits. DB outages are redelivered.
        jobs.enqueue(eventId, booked.rideId(), List.copyOf(booked.tripIds()));
    }
}
