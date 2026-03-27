package com.loopers.application.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.metrics.ProductMetrics;
import com.loopers.infrastructure.event.EventHandledJpaRepository;
import com.loopers.infrastructure.metrics.ProductMetricsJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * [통합 테스트 - Service Integration]
 *
 * 테스트 대상: MetricsEventService (Application Layer - Streamer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Service → Repository → Database (Testcontainers)
 *
 * 검증 목적:
 * - product_metrics upsert가 실제 DB에서 올바르게 동작하는지
 * - event_handled 기반 멱등 처리가 실제 DB에서 보장되는지
 */
@SpringBootTest
@DisplayName("MetricsEventService 통합 테스트")
class MetricsEventServiceIntegrationTest {

    @Autowired
    private MetricsEventService metricsEventService;

    @Autowired
    private ProductMetricsJpaRepository productMetricsJpaRepository;

    @Autowired
    private EventHandledJpaRepository eventHandledJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private JsonNode toJsonNode(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("PRODUCT_LIKED 이벤트 처리")
    class ProcessProductLiked {

        @Test
        @DisplayName("좋아요 이벤트를 처리하면 like_count가 증가하고 event_handled에 기록된다")
        void incrementsLikeCount() {
            // given
            String eventId = UUID.randomUUID().toString();
            JsonNode data = toJsonNode("{\"productId\":1,\"memberId\":100,\"liked\":true}");

            // when
            boolean result = metricsEventService.processProductLiked(eventId, data);

            // then
            Optional<ProductMetrics> metrics = productMetricsJpaRepository.findById(1L);
            assertAll(
                    () -> assertThat(result).isTrue(),
                    () -> assertThat(metrics).isPresent(),
                    () -> assertThat(metrics.get().getLikeCount()).isEqualTo(1),
                    () -> assertThat(eventHandledJpaRepository.existsById(eventId)).isTrue()
            );
        }

        @Test
        @DisplayName("좋아요 취소 이벤트를 처리하면 like_count가 감소한다")
        void decrementsLikeCount() {
            // given — 먼저 좋아요 1회
            metricsEventService.processProductLiked(
                    UUID.randomUUID().toString(),
                    toJsonNode("{\"productId\":1,\"memberId\":100,\"liked\":true}")
            );

            String eventId = UUID.randomUUID().toString();
            JsonNode data = toJsonNode("{\"productId\":1,\"memberId\":100,\"liked\":false}");

            // when
            metricsEventService.processProductLiked(eventId, data);

            // then
            ProductMetrics metrics = productMetricsJpaRepository.findById(1L).get();
            assertThat(metrics.getLikeCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("같은 eventId로 중복 호출하면 두 번째는 skip한다")
        void skipsDuplicateEvent() {
            // given
            String eventId = UUID.randomUUID().toString();
            JsonNode data = toJsonNode("{\"productId\":1,\"memberId\":100,\"liked\":true}");
            metricsEventService.processProductLiked(eventId, data);

            // when
            boolean secondResult = metricsEventService.processProductLiked(eventId, data);

            // then
            assertThat(secondResult).isFalse();
            assertThat(productMetricsJpaRepository.findById(1L).get().getLikeCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("PRODUCT_VIEWED 이벤트 처리")
    class ProcessProductViewed {

        @Test
        @DisplayName("조회 이벤트를 처리하면 view_count가 증가한다")
        void incrementsViewCount() {
            // given
            String eventId = UUID.randomUUID().toString();
            JsonNode data = toJsonNode("{\"productId\":1,\"memberId\":100}");

            // when
            boolean result = metricsEventService.processProductViewed(eventId, data);

            // then
            ProductMetrics metrics = productMetricsJpaRepository.findById(1L).get();
            assertAll(
                    () -> assertThat(result).isTrue(),
                    () -> assertThat(metrics.getViewCount()).isEqualTo(1)
            );
        }

        @Test
        @DisplayName("없는 상품에 대해 조회 이벤트가 오면 새 metrics가 생성된다")
        void createsNewMetrics() {
            // given
            String eventId = UUID.randomUUID().toString();
            JsonNode data = toJsonNode("{\"productId\":999,\"memberId\":100}");

            // when
            metricsEventService.processProductViewed(eventId, data);

            // then
            Optional<ProductMetrics> metrics = productMetricsJpaRepository.findById(999L);
            assertAll(
                    () -> assertThat(metrics).isPresent(),
                    () -> assertThat(metrics.get().getViewCount()).isEqualTo(1),
                    () -> assertThat(metrics.get().getLikeCount()).isEqualTo(0),
                    () -> assertThat(metrics.get().getOrderCount()).isEqualTo(0)
            );
        }
    }

    @Nested
    @DisplayName("ORDER_PAID 이벤트 처리")
    class ProcessOrderPaid {

        @Test
        @DisplayName("주문 이벤트를 처리하면 상품별 order_count가 증가한다")
        void incrementsOrderCountPerProduct() {
            // given
            String eventId = UUID.randomUUID().toString();
            JsonNode data = toJsonNode("""
                    {
                        "orderId": 1,
                        "memberId": 100,
                        "totalAmount": 50000,
                        "orderedProducts": [
                            {"productId": 1, "quantity": 2},
                            {"productId": 2, "quantity": 3}
                        ]
                    }
                    """);

            // when
            boolean result = metricsEventService.processOrderPaid(eventId, data);

            // then
            assertAll(
                    () -> assertThat(result).isTrue(),
                    () -> assertThat(productMetricsJpaRepository.findById(1L).get().getOrderCount()).isEqualTo(2),
                    () -> assertThat(productMetricsJpaRepository.findById(2L).get().getOrderCount()).isEqualTo(3)
            );
        }
    }
}
