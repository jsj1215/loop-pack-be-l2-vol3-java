package com.loopers.application.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.event.EventHandled;
import com.loopers.domain.event.EventHandledRepository;
import com.loopers.domain.metrics.ProductMetrics;
import com.loopers.domain.metrics.ProductMetricsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MetricsEventService 테스트")
@ExtendWith(MockitoExtension.class)
class MetricsEventServiceTest {

    @Mock
    private EventHandledRepository eventHandledRepository;

    @Mock
    private ProductMetricsRepository productMetricsRepository;

    private MetricsEventService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new MetricsEventService(eventHandledRepository, productMetricsRepository);
    }

    /**
     * 멱등 처리 위치에 대한 고민 — Consumer에서 체크(접근 A) vs Service TX 내부에서 체크(접근 B).
     *
     * At Least Once 환경에서 같은 이벤트가 중복 수신될 수 있다.
     * event_handled 테이블로 멱등 처리를 하는데, 이 체크를 어디서 할 것인가에 따라
     * 동시성 안전성이 달라진다.
     */
    @Nested
    @DisplayName("고민 1: 멱등 처리 위치 비교 — 접근 A(Consumer 체크) vs 접근 B(Service TX 체크)")
    class IdempotencyLocationComparison {

        /**
         * [접근 A의 문제 재현]
         *
         * 접근 A에서는 Consumer가 TX 밖에서 existsByEventId()를 호출한다.
         * 이 시뮬레이션은 두 스레드가 동시에 existsByEventId → false를 받는 상황을 재현한다.
         *
         * <pre>
         * Thread-1: Consumer.existsByEventId("uuid-1") → false  (TX 밖)
         * Thread-2: Consumer.existsByEventId("uuid-1") → false  (TX 밖)
         * Thread-1: service.processProductLiked("uuid-1") → likeCount +1
         * Thread-2: service.processProductLiked("uuid-1") → likeCount +1  ← 중복!
         * </pre>
         *
         * 접근 A에서는 Consumer의 체크와 서비스 호출 사이에 TOCTOU 갭이 존재하여,
         * 서비스 내부에 별도 방어 로직이 없으면 같은 이벤트가 2번 처리된다.
         */
        @Test
        @DisplayName("[접근 A 문제 재현] Consumer에서 체크 후 서비스를 2번 호출하면 — 멱등 체크 없이 중복 집계된다")
        void approachA_toctouAllowsDuplicateProcessing() throws Exception {
            // given — 접근 A 시뮬레이션: Consumer가 existsByEventId → false 후 서비스 호출
            // 서비스 내부에 멱등 체크가 없다고 가정하기 위해, existsByEventId가 항상 false를 반환
            JsonNode data = objectMapper.readTree("{\"productId\":10,\"liked\":true}");
            ProductMetrics sharedMetrics = new ProductMetrics(10L);

            // 두 번의 호출 모두 existsByEventId → false (TOCTOU 갭 시뮬레이션)
            when(eventHandledRepository.existsByEventId("uuid-toctou")).thenReturn(false);
            when(productMetricsRepository.findByProductId(10L)).thenReturn(Optional.of(sharedMetrics));

            // when — 같은 eventId로 서비스를 2번 호출 (두 스레드가 동시에 Consumer 체크를 통과한 상황)
            service.processProductLiked("uuid-toctou", data);  // 첫 번째: likeCount = 1
            service.processProductLiked("uuid-toctou", data);  // 두 번째: likeCount = 2 ← 중복!

            // then — 접근 A에서는 서비스가 멱등 체크를 하지 않으면 2번 집계됨
            // (실제 접근 B 구현에서는 existsByEventId가 두 번째 호출에서 true를 반환하거나
            //  UNIQUE 위반으로 방어하지만, 이 테스트는 접근 A의 취약점을 보여주기 위한 것)
            assertThat(sharedMetrics.getLikeCount()).isEqualTo(2); // 중복 집계 발생!
            verify(productMetricsRepository, times(2)).save(any()); // save도 2번 호출됨
        }

        /**
         * [접근 B 해결 — 1차 방어] Service TX 내부에서 existsByEventId 체크.
         *
         * 서비스 내부의 @Transactional 안에서 체크하므로,
         * 첫 번째 처리가 event_handled INSERT까지 완료(커밋)된 후
         * 두 번째 호출에서는 existsByEventId → true가 되어 skip한다.
         */
        @Test
        @DisplayName("[접근 B 해결 1차] TX 내부에서 이미 처리된 이벤트를 감지하면 skip한다")
        void approachB_skipAlreadyHandled_insideTransaction() throws Exception {
            // given
            JsonNode data = objectMapper.readTree("{\"productId\":10,\"liked\":true}");
            when(eventHandledRepository.existsByEventId("uuid-dup")).thenReturn(true);

            // when
            boolean result = service.processProductLiked("uuid-dup", data);

            // then — 집계하지 않고 skip
            assertThat(result).isFalse();
            verify(productMetricsRepository, never()).save(any());
            verify(eventHandledRepository, never()).save(any());
        }

        /**
         * [접근 B 해결 — 2차 방어] UNIQUE 제약으로 동시 요청 최종 방어.
         *
         * 극히 드물게 두 TX가 동시에 existsByEventId → false를 받아 둘 다 처리에 진입하는 경우,
         * event_handled PK UNIQUE 제약이 최종 방어선 역할을 한다.
         * UNIQUE 위반 시 DataIntegrityViolationException이 발생하여 TX 전체가 롤백되므로,
         * 집계(likeCount +1)도 함께 롤백되어 중복 집계가 방지된다.
         */
        @Test
        @DisplayName("[접근 B 해결 2차] UNIQUE 제약 위반 시 TX 롤백으로 중복 집계를 방지한다")
        void approachB_uniqueConstraintPreventsduplicateProcessing() throws Exception {
            // given — 동시 요청으로 existsByEventId가 false를 반환하지만, save 시 UNIQUE 위반
            JsonNode data = objectMapper.readTree("{\"productId\":10,\"liked\":true}");
            when(eventHandledRepository.existsByEventId("uuid-race")).thenReturn(false);
            when(productMetricsRepository.findByProductId(10L)).thenReturn(Optional.of(new ProductMetrics(10L)));
            when(eventHandledRepository.save(any(EventHandled.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate entry 'uuid-race'"));

            // when & then — UNIQUE 위반 예외가 전파되어 TX 전체 롤백
            // 이로 인해 productMetrics의 likeCount +1도 함께 롤백됨 → 중복 집계 방지
            assertThatThrownBy(() -> service.processProductLiked("uuid-race", data))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("PRODUCT_LIKED 이벤트 처리")
    class ProcessProductLiked {

        @Test
        @DisplayName("liked=true 시 like_count를 증가시키고 멱등 기록을 남긴다")
        void incrementsLikeCount() throws Exception {
            // given
            JsonNode data = objectMapper.readTree("{\"productId\":10,\"memberId\":100,\"liked\":true}");
            when(eventHandledRepository.existsByEventId("uuid-1")).thenReturn(false);
            when(productMetricsRepository.findByProductId(10L)).thenReturn(Optional.of(new ProductMetrics(10L)));

            // when
            boolean result = service.processProductLiked("uuid-1", data);

            // then
            assertThat(result).isTrue();
            ArgumentCaptor<ProductMetrics> captor = ArgumentCaptor.forClass(ProductMetrics.class);
            verify(productMetricsRepository).save(captor.capture());
            assertThat(captor.getValue().getLikeCount()).isEqualTo(1);
            verify(eventHandledRepository).save(any(EventHandled.class));
        }

        @Test
        @DisplayName("liked=false 시 like_count를 감소시킨다")
        void decrementsLikeCount() throws Exception {
            // given
            JsonNode data = objectMapper.readTree("{\"productId\":10,\"memberId\":100,\"liked\":false}");
            when(eventHandledRepository.existsByEventId("uuid-2")).thenReturn(false);
            ProductMetrics metrics = new ProductMetrics(10L);
            metrics.incrementLikeCount();
            when(productMetricsRepository.findByProductId(10L)).thenReturn(Optional.of(metrics));

            // when
            service.processProductLiked("uuid-2", data);

            // then
            ArgumentCaptor<ProductMetrics> captor = ArgumentCaptor.forClass(ProductMetrics.class);
            verify(productMetricsRepository).save(captor.capture());
            assertThat(captor.getValue().getLikeCount()).isZero();
        }
    }

    @Nested
    @DisplayName("PRODUCT_VIEWED 이벤트 처리")
    class ProcessProductViewed {

        @Test
        @DisplayName("view_count를 증가시킨다")
        void incrementsViewCount() throws Exception {
            // given
            JsonNode data = objectMapper.readTree("{\"productId\":10}");
            when(eventHandledRepository.existsByEventId("uuid-3")).thenReturn(false);
            when(productMetricsRepository.findByProductId(10L)).thenReturn(Optional.of(new ProductMetrics(10L)));

            // when
            service.processProductViewed("uuid-3", data);

            // then
            ArgumentCaptor<ProductMetrics> captor = ArgumentCaptor.forClass(ProductMetrics.class);
            verify(productMetricsRepository).save(captor.capture());
            assertThat(captor.getValue().getViewCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("메트릭이 없는 상품은 새로 생성하여 집계한다")
        void createsNewMetrics() throws Exception {
            // given
            JsonNode data = objectMapper.readTree("{\"productId\":999}");
            when(eventHandledRepository.existsByEventId("uuid-new")).thenReturn(false);
            when(productMetricsRepository.findByProductId(999L)).thenReturn(Optional.empty());

            // when
            service.processProductViewed("uuid-new", data);

            // then
            ArgumentCaptor<ProductMetrics> captor = ArgumentCaptor.forClass(ProductMetrics.class);
            verify(productMetricsRepository).save(captor.capture());
            assertThat(captor.getValue().getProductId()).isEqualTo(999L);
            assertThat(captor.getValue().getViewCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("ORDER_PAID 이벤트 처리")
    class ProcessOrderPaid {

        @Test
        @DisplayName("상품별 order_count를 증가시킨다")
        void incrementsOrderCountPerProduct() throws Exception {
            // given
            JsonNode data = objectMapper.readTree("""
                    {
                        "orderId": 1,
                        "orderedProducts": [
                            {"productId": 10, "quantity": 2},
                            {"productId": 20, "quantity": 1}
                        ]
                    }
                    """);
            when(eventHandledRepository.existsByEventId("uuid-4")).thenReturn(false);
            when(productMetricsRepository.findByProductId(10L)).thenReturn(Optional.of(new ProductMetrics(10L)));
            when(productMetricsRepository.findByProductId(20L)).thenReturn(Optional.of(new ProductMetrics(20L)));

            // when
            service.processOrderPaid("uuid-4", data);

            // then
            ArgumentCaptor<ProductMetrics> captor = ArgumentCaptor.forClass(ProductMetrics.class);
            verify(productMetricsRepository, times(2)).save(captor.capture());

            ProductMetrics product10 = captor.getAllValues().stream()
                    .filter(m -> m.getProductId().equals(10L)).findFirst().orElseThrow();
            ProductMetrics product20 = captor.getAllValues().stream()
                    .filter(m -> m.getProductId().equals(20L)).findFirst().orElseThrow();

            assertThat(product10.getOrderCount()).isEqualTo(2);
            assertThat(product20.getOrderCount()).isEqualTo(1);
            verify(eventHandledRepository).save(any(EventHandled.class));
        }
    }
}
