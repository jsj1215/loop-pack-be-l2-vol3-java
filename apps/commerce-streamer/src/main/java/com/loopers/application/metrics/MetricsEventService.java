package com.loopers.application.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.event.EventHandled;
import com.loopers.domain.event.EventHandledRepository;
import com.loopers.domain.metrics.ProductMetrics;
import com.loopers.domain.metrics.ProductMetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka Consumer에서 호출하는 메트릭 집계 서비스.
 *
 * TX 커밋과 ACK 순서:
 * <pre>
 * Consumer.consume()
 *   ├─ metricsEventService.processXxx()  ← @Transactional 시작/커밋
 *   └─ acknowledgment.acknowledge()      ← TX 커밋 완료 후 ACK 전송
 * </pre>
 *
 * 멱등 처리 위치에 대한 고민 — Consumer vs Service 내부:
 *
 * 접근 A (초기 구현): Consumer에서 {@code existsByEventId()} 체크 후 서비스 호출.
 * 단순하지만 Consumer의 체크(TX 밖)와 서비스의 처리(TX 안) 사이에 TOCTOU 갭이 존재한다.
 * 동일 이벤트가 중복 수신되어 두 스레드가 동시에 체크를 통과하면 둘 다 처리에 진입할 수 있다.
 *
 * <pre>
 * Thread-1: existsByEventId("uuid-1") → false  ← 아직 INSERT 전
 * Thread-2: existsByEventId("uuid-1") → false  ← 아직 INSERT 전
 * Thread-1: processProductLiked("uuid-1") → likeCount +1
 * Thread-2: processProductLiked("uuid-1") → likeCount +1  ← 중복 집계!
 * </pre>
 *
 * 접근 B (최종 구현): Service 내부 {@code @Transactional} 안에서 멱등 체크를 수행하고,
 * {@code event_handled} 테이블의 PK(UNIQUE) 제약으로 최종 방어한다.
 * {@code DataIntegrityViolationException} 발생 시 이미 처리된 것으로 간주하고 skip.
 *
 * <pre>
 * Thread-1: @Transactional { existsByEventId → false → process → event_handled INSERT ✅ }
 * Thread-2: @Transactional { existsByEventId → false → process → event_handled INSERT → UNIQUE 위반 → rollback }
 * → Thread-2의 집계도 롤백되므로 중복 집계 방지 ✅
 * </pre>
 *
 * 접근 B를 선택한 이유: At Least Once 환경에서 같은 메시지가 거의 동시에 도착할 수 있고,
 * 멱등 처리가 깨지면 집계 데이터의 정확성이 훼손된다. UNIQUE 제약을 최종 방어선으로 사용하면
 * race condition 없이 정확한 멱등 처리가 보장된다.
 *
 * @see com.loopers.interfaces.consumer.CatalogEventConsumer
 * @see com.loopers.interfaces.consumer.OrderEventConsumer
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class MetricsEventService {

    private final EventHandledRepository eventHandledRepository;
    private final ProductMetricsRepository productMetricsRepository;

    /**
     * PRODUCT_LIKED 이벤트를 처리한다: 멱등 체크 + like_count 증감.
     *
     * 멱등 체크를 TX 내부에서 수행하여 TOCTOU 갭을 최소화하고,
     * event_handled PK UNIQUE 제약으로 동시 요청 시 중복 처리를 방지한다.
     *
     * @param eventId 이벤트 ID (멱등 키)
     * @param data    이벤트 데이터 (productId, memberId, liked)
     * @return true면 정상 처리, false면 중복 이벤트로 skip
     */
    @Transactional
    public boolean processProductLiked(String eventId, JsonNode data) {
        if (eventHandledRepository.existsByEventId(eventId)) {
            log.debug("이미 처리된 이벤트 — skip (TX 내부 체크): eventId={}", eventId);
            return false;
        }

        Long productId = data.path("productId").asLong();
        boolean liked = data.path("liked").asBoolean();

        ProductMetrics metrics = getOrCreateMetrics(productId);
        if (liked) {
            metrics.incrementLikeCount();
        } else {
            metrics.decrementLikeCount();
        }
        productMetricsRepository.save(metrics);

        try {
            eventHandledRepository.save(new EventHandled(eventId));
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 제약 위반 → 동시 요청으로 다른 스레드가 먼저 처리 완료
            // 이 TX의 집계도 롤백되므로 중복 집계 방지
            log.info("event_handled UNIQUE 제약 위반 — 동시 중복 요청 방어: eventId={}", eventId);
            throw e; // TX 롤백을 위해 예외 전파
        }

        return true;
    }

    /**
     * PRODUCT_VIEWED 이벤트를 처리한다: 멱등 체크 + view_count +1.
     *
     * @param eventId 이벤트 ID (멱등 키)
     * @param data    이벤트 데이터 (productId)
     * @return true면 정상 처리, false면 중복 이벤트로 skip
     */
    @Transactional
    public boolean processProductViewed(String eventId, JsonNode data) {
        if (eventHandledRepository.existsByEventId(eventId)) {
            log.debug("이미 처리된 이벤트 — skip (TX 내부 체크): eventId={}", eventId);
            return false;
        }

        Long productId = data.path("productId").asLong();

        ProductMetrics metrics = getOrCreateMetrics(productId);
        metrics.incrementViewCount();
        productMetricsRepository.save(metrics);

        try {
            eventHandledRepository.save(new EventHandled(eventId));
        } catch (DataIntegrityViolationException e) {
            log.info("event_handled UNIQUE 제약 위반 — 동시 중복 요청 방어: eventId={}", eventId);
            throw e;
        }

        return true;
    }

    /**
     * ORDER_PAID 이벤트를 처리한다: 멱등 체크 + 상품별 order_count 증가.
     *
     * @param eventId 이벤트 ID (멱등 키)
     * @param data    이벤트 데이터 (orderedProducts 배열 포함)
     * @return true면 정상 처리, false면 중복 이벤트로 skip
     */
    @Transactional
    public boolean processOrderPaid(String eventId, JsonNode data) {
        if (eventHandledRepository.existsByEventId(eventId)) {
            log.debug("이미 처리된 이벤트 — skip (TX 내부 체크): eventId={}", eventId);
            return false;
        }

        JsonNode orderedProducts = data.path("orderedProducts");

        if (!orderedProducts.isArray()) {
            log.warn("ORDER_PAID: orderedProducts가 배열이 아님 — skip");
            return false;
        }

        for (JsonNode item : orderedProducts) {
            Long productId = item.path("productId").asLong();
            int quantity = item.path("quantity").asInt();

            ProductMetrics metrics = getOrCreateMetrics(productId);
            metrics.addOrderCount(quantity);
            productMetricsRepository.save(metrics);
        }

        try {
            eventHandledRepository.save(new EventHandled(eventId));
        } catch (DataIntegrityViolationException e) {
            log.info("event_handled UNIQUE 제약 위반 — 동시 중복 요청 방어: eventId={}", eventId);
            throw e;
        }

        return true;
    }

    // 상품 메트릭을 조회하거나, 없으면 새로 생성한다.
    // 같은 productId는 같은 Kafka 파티션에서 순차 처리되므로 동시 INSERT race는 사실상 없다.
    // 리밸런스 등 극단적 케이스에서 PK 충돌이 발생하면 TX 롤백 후 재시도로 자연 복구된다.
    private ProductMetrics getOrCreateMetrics(Long productId) {
        return productMetricsRepository.findByProductId(productId)
                .orElseGet(() -> new ProductMetrics(productId));
    }
}
