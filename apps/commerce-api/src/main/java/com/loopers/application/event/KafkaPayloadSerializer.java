package com.loopers.application.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

// Kafka 발행용 envelope 생성 및 JSON 직렬화 유틸리티.
// KafkaEventPublishListener와 CouponIssueFacade에서 공통으로 사용한다.
@RequiredArgsConstructor
@Component
public class KafkaPayloadSerializer {

    private final ObjectMapper objectMapper;

    public Map<String, Object> buildEnvelope(String eventId, String eventType, Object data) {
        return Map.of(
                "eventId", eventId,
                "eventType", eventType,
                "data", data
        );
    }

    public String serialize(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "Kafka payload 직렬화 실패");
        }
    }
}
