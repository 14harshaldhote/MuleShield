package io.muleshield.risk.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import io.muleshield.core.events.Topics;

/** Creates the topics on first start (in production they are provisioned with the cluster). */
@Configuration(proxyBeanMethods = false)
class KafkaTopics {

    @Bean
    NewTopic payments() {
        return TopicBuilder.name(Topics.PAYMENTS).partitions(6).build();
    }

    @Bean
    NewTopic flags() {
        return TopicBuilder.name(Topics.ACCOUNT_FLAGS).partitions(6).build();
    }

    @Bean
    NewTopic holds() {
        return TopicBuilder.name(Topics.HOLDS).partitions(6).build();
    }

    @Bean
    NewTopic reports() {
        return TopicBuilder.name(Topics.REPORTS).partitions(6).build();
    }

    @Bean
    NewTopic signals() {
        return TopicBuilder.name(Topics.SHARED_SIGNALS).partitions(6).build();
    }
}
