package com.loopers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter;

import java.util.HashMap;
import java.util.Map;

/**
 * Metrics Consumer 전용 Kafka 설정.
 *
 * 단건 리스너 팩토리를 정의한다.
 * modules/kafka의 BATCH_LISTENER와 별도로 운영되며, commerce-streamer에만 적용된다.
 *
 * 단건 리스너를 사용하는 이유:
 *  - 멱등 처리(event_handled 체크)가 단순하고 직관적
 *  - 실패 시 해당 메시지만 재수신 (배치는 전체 재수신)
 *  - 배치 처리는 Nice-to-Have로, 필요 시 확장
 */
@Configuration
public class MetricsKafkaConfig {

    public static final String SINGLE_LISTENER = "SINGLE_LISTENER_METRICS";

    /**
     * 단건 메시지 처리용 리스너 컨테이너 팩토리.
     *
     * Manual ACK 모드를 사용하여 메시지 처리 완료 후 명시적으로 ACK를 보낸다.
     * 처리 중 실패하면 ACK를 보내지 않아 Kafka가 다시 전달한다.
     */
    @Bean(name = SINGLE_LISTENER)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> singleListenerContainerFactory(
            KafkaProperties kafkaProperties,
            ObjectMapper objectMapper
    ) {
        Map<String, Object> consumerConfig = new HashMap<>(kafkaProperties.buildConsumerProperties());

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerConfig));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setRecordMessageConverter(new ByteArrayJsonMessageConverter(objectMapper));
        // 토픽 파티션 수 이상이면 idle consumer가 발생한다.
        // coupon-issue-requests: partition key=couponId로 같은 쿠폰 요청의 순차 처리를 보장한다.
        factory.setConcurrency(3);
        factory.setBatchListener(false); // 단건 리스너
        return factory;
    }
}
