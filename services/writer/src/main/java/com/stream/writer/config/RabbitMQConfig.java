package com.stream.writer.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Bean
    public Queue imageAnalyzedQueue() {
        return new Queue("image.analyzed", true); // durable: true
    }
}
