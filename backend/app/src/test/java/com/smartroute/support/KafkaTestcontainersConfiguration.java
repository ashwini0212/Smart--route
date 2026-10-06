package com.smartroute.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Kafka broker (same image as docker-compose) for the tests that exercise publishing and consuming.
 *
 * <p>Only {@code KafkaEventFlowTest} imports this, so the rest of the suite keeps its single context and does
 * not pay for a broker it does not use.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        // Auto-creation off, as in docker-compose: a test must fail if the application does not create its
        // own topics, instead of silently getting one-partition topics made by the first publish.
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    }
}
