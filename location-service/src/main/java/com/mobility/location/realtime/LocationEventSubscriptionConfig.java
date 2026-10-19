package com.mobility.location.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
public class LocationEventSubscriptionConfig {

    private static final Logger log =
            LoggerFactory.getLogger(LocationEventSubscriptionConfig.class);

    @Bean
    public RedisMessageListenerContainer locationEventListenerContainer(
            RedisConnectionFactory connectionFactory,
            DriverLocationEventListener listener,
            @Value("${location.tracking.events-channel}") String channel
    ) {
        if (channel == null || channel.isBlank()) {
            throw new IllegalStateException(
                    "Location events channel must be configured"
            );
        }

        RedisMessageListenerContainer container =
                new RedisMessageListenerContainer();

        container.setConnectionFactory(connectionFactory);

        container.addMessageListener(
                listener,
                new ChannelTopic(channel)
        );

        container.setErrorHandler(exception ->
                log.error(
                        "Location Redis subscription error: errorType={}",
                        exception.getClass().getSimpleName()
                )
        );

        return container;
    }
}