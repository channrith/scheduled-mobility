package com.mobility.core.dispatch.automatic;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import tools.jackson.databind.json.JsonMapper;

class RideBookedListenerTest {
    @Test
    void malformedMessagesAreRejectedBeforeTouchingDatabase() {
        var jobs = mock(AssignmentJobs.class);
        var listener = new RideBookedListener(JsonMapper.builder().build(), jobs);
        for (String invalid : new String[]{"not json", "{}", "{\"type\":\"booking.trip-assigned\"}"}) {
            assertThatThrownBy(() -> listener.receive(new Message(invalid.getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
        verifyNoInteractions(jobs);
    }
}
