package com.mobility.core.dispatch.automatic;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declare the queue even while rollout is disabled, so bookings are not lost during rollout. */
@Configuration(proxyBeanMethods = false)
class AutomaticAssignmentConfiguration {
    static final String QUEUE = "dispatch.ride-booked";

    @Bean
    SimpleRabbitListenerContainerFactory automaticAssignmentListenerFactory(
            ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setPrefetchCount(10);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxRetries(3).backOffOptions(1000, 2, 5000)
                .recoverer(new RejectAndDontRequeueRecoverer()).build());
        return factory;
    }

    @Bean
    Declarables automaticAssignmentQueues(TopicExchange eventsExchange) {
        var dead = new DirectExchange("dispatch.dead", true, false);
        var failed = QueueBuilder.durable(QUEUE + ".dead").build();
        var queue = QueueBuilder.durable(QUEUE).deadLetterExchange(dead.getName())
                .deadLetterRoutingKey(QUEUE).build();
        return new Declarables(dead, failed, queue,
                BindingBuilder.bind(failed).to(dead).with(QUEUE),
                BindingBuilder.bind(queue).to(eventsExchange).with("booking.ride-booked"));
    }
}
