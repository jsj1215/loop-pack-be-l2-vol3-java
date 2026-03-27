package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka 발행 방식에 대한 고민을 테스트로 기록한다.
 *
 * 비동기(whenComplete)와 동기(.get()) 두 접근의 차이를 테스트로 비교하고,
 * 동기 방식을 채택한 이유를 검증한다.
 */
@DisplayName("KafkaOutboxEventPublisher 테스트")
@ExtendWith(MockitoExtension.class)
class KafkaOutboxEventPublisherTest {

    @Mock
    private KafkaTemplate<Object, Object> kafkaTemplate;

    private KafkaOutboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaOutboxEventPublisher(kafkaTemplate);
    }

    /**
     * 비동기 발행(접근 A)의 문제점을 시뮬레이션한다.
     *
     * 비동기 방식에서는 kafkaTemplate.send()가 즉시 리턴하므로,
     * 호출자가 발행 성공/실패를 알 수 없다. 콜백에서만 결과를 받으므로
     * markPublished()를 호출하는 시점이 복잡해진다.
     */
    @Nested
    @DisplayName("고민 2-A: 비동기 발행 방식의 문제점")
    class AsyncPublishProblem {

        @Test
        @DisplayName("[접근 A 문제 재현] 비동기 발행 실패 시 호출자는 예외를 받지 못한다")
        void asyncFailure_callerCannotDetect() {
            // given — 비동기 발행 시뮬레이션: 발행은 실패하지만 Future가 바로 리턴됨
            OutboxEvent event = OutboxEvent.create("ORDER", "1", "ORDER_PAID", "order-events", "{}");
            CompletableFuture<SendResult<Object, Object>> failedFuture = new CompletableFuture<>();

            // 비동기 방식이라면 send() 호출 시점에는 아직 완료되지 않은 Future를 받음
            when(kafkaTemplate.send(any(String.class), any(), any())).thenReturn(failedFuture);

            // when — 비동기 방식이었다면 이렇게 콜백을 등록하고 바로 리턴
            AtomicBoolean failureDetected = new AtomicBoolean(false);
            kafkaTemplate.send("order-events", "1", "{}")
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            failureDetected.set(true); // 콜백에서만 실패를 감지
                        }
                    });

            // 이 시점에서 호출자는 발행 결과를 모른다
            assertThat(failureDetected.get()).isFalse(); // 아직 콜백 실행 안 됨

            // 나중에 실패가 발생해야 콜백이 실행됨
            failedFuture.completeExceptionally(new RuntimeException("Kafka broker unavailable"));
            assertThat(failureDetected.get()).isTrue(); // 이제야 감지

            // then — 비동기 방식에서는 send() 직후에 markPublished()를 호출하면
            // 아직 발행 결과를 모르는 상태에서 published=true가 되어 위험하다.
            // 콜백에서 markPublished()를 호출하려면 별도 TX를 열어야 하고,
            // 콜백 스레드에서 JPA 영속성 컨텍스트를 관리해야 하는 복잡도가 추가된다.
        }
    }

    /**
     * 동기 발행(접근 B, 채택)의 장점을 검증한다.
     *
     * 동기 방식에서는 .get()으로 블로킹하여 발행 성공/실패를 즉시 알 수 있다.
     * 메서드 리턴 = 발행 완료 확정이므로, markPublished() 호출 타이밍이 명확하다.
     */
    @Nested
    @DisplayName("고민 2-B: 동기 발행 방식 (채택) — 결과 즉시 확인 가능")
    class SyncPublishApproach {

        @Test
        @DisplayName("[접근 B 장점] 발행 성공 시 메서드 리턴 = 발행 완료 확정")
        void syncSuccess_immediateConfirmation() {
            // given
            OutboxEvent event = OutboxEvent.create("ORDER", "1", "ORDER_PAID", "order-events", "{\"orderId\":1}");
            SendResult<Object, Object> sendResult = new SendResult<>(
                    new ProducerRecord<>("order-events", "1", "{\"orderId\":1}"),
                    new RecordMetadata(new TopicPartition("order-events", 0), 0, 0, 0, 0, 0));
            when(kafkaTemplate.send(any(String.class), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(sendResult));

            // when
            publisher.publish(event);

            // then — 메서드가 예외 없이 리턴 = 발행 성공 확정
            // 호출자(스케줄러)는 바로 markPublished()를 호출할 수 있다
            verify(kafkaTemplate).send(eq("order-events"), eq("1"), eq("{\"orderId\":1}"));
        }

        @Test
        @DisplayName("[접근 B 장점] 발행 실패 시 예외가 즉시 전파되어 호출자가 감지한다")
        void syncFailure_immediateException() {
            // given
            OutboxEvent event = OutboxEvent.create("ORDER", "1", "ORDER_PAID", "order-events", "{}");
            CompletableFuture<SendResult<Object, Object>> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new RuntimeException("Kafka broker unavailable"));
            when(kafkaTemplate.send(any(String.class), any(), any())).thenReturn(failedFuture);

            // when & then — 동기 방식: 예외가 호출자에게 즉시 전파
            // 스케줄러는 이 예외를 catch하여 markPublished()를 호출하지 않고 skip
            assertThatThrownBy(() -> publisher.publish(event))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Kafka 발행 실패");
        }

        @Test
        @DisplayName("[접근 B 장점] 실패한 이벤트는 published=false를 유지하여 재시도 대상이 된다")
        void failedEvent_remainsUnpublished_forRetry() {
            // given
            OutboxEvent event = OutboxEvent.create("PRODUCT", "10", "PRODUCT_LIKED", "catalog-events", "{}");
            CompletableFuture<SendResult<Object, Object>> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new RuntimeException("Network timeout"));
            when(kafkaTemplate.send(any(String.class), any(), any())).thenReturn(failedFuture);

            // when
            try {
                publisher.publish(event);
            } catch (RuntimeException ignored) {
                // 스케줄러가 catch하는 상황 시뮬레이션
            }

            // then — published=false 유지 → 다음 폴링에서 재시도 대상
            assertThat(event.isPublished()).isFalse();
        }
    }
}
