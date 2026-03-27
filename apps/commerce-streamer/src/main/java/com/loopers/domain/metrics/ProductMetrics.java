package com.loopers.domain.metrics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * 상품별 비동기 집계 메트릭 엔티티.
 *
 * Kafka Consumer가 이벤트를 수신하여 상품별로 집계한 결과를 저장한다.
 * commerce-api의 {@code Product.likeCount}와는 별도로 운영되며,
 * 통계/분석 용도로 사용된다.
 *
 * 기존 Product.likeCount와의 관계:
 *  - {@code Product.likeCount}: commerce-api에서 실시간 반영 (API 응답, 정렬용)
 *  - {@code ProductMetrics}: commerce-streamer에서 비동기 집계 (통계/분석용)
 *
 * 두 값이 일시적으로 불일치할 수 있으나, 역할이 다르므로 허용한다.
 *
 * 집계 방식:
 * 매 이벤트를 증감(+1/-1) 처리한다.
 * 같은 productId는 같은 Kafka 파티션 → 같은 Consumer가 순서대로 처리하므로
 * {@code event_handled} 기반 멱등 처리로 중복 방지가 충분하다.
 *
 * @Version(낙관적 락) 미적용 사유:
 * 증감 방식은 모든 이벤트를 순서대로 처리해야 정합성이 유지된다.
 * @Version을 추가하면 동시 갱신 시 OptimisticLockException이 발생하여
 * 정상 메시지 처리가 실패할 수 있다. 파티션 키 기반 순차 처리 + event_handled
 * UNIQUE 제약이 동시성 제어를 대체하므로 @Version은 불필요하다.
 */
@Entity
@Table(name = "product_metrics")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductMetrics {

    /** 상품 ID — Product 테이블의 PK와 동일 */
    @Id
    @Column(name = "product_id")
    private Long productId;

    /** 좋아요 수 (비동기 집계) */
    @Column(name = "like_count", nullable = false)
    private long likeCount;

    /** 조회수 (비동기 집계) */
    @Column(name = "view_count", nullable = false)
    private long viewCount;

    /** 판매 수량 (비동기 집계 — 주문 상품별 quantity 합산) */
    @Column(name = "order_count", nullable = false)
    private long orderCount;

    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;

    @PrePersist
    private void prePersist() {
        this.updatedAt = ZonedDateTime.now();
    }

    @PreUpdate
    private void preUpdate() {
        this.updatedAt = ZonedDateTime.now();
    }

    /**
     * 신규 상품 메트릭을 생성한다. 모든 카운트 0으로 초기화.
     */
    public ProductMetrics(Long productId) {
        this.productId = productId;
        this.likeCount = 0;
        this.viewCount = 0;
        this.orderCount = 0;
    }

    /** 좋아요 수를 1 증가시킨다. */
    public void incrementLikeCount() {
        this.likeCount++;
    }

    /** 좋아요 수를 1 감소시킨다. 0 미만으로 내려가지 않는다. */
    public void decrementLikeCount() {
        if (this.likeCount > 0) {
            this.likeCount--;
        }
    }

    /** 조회수를 1 증가시킨다. */
    public void incrementViewCount() {
        this.viewCount++;
    }

    /** 판매 수량을 주어진 수량만큼 증가시킨다. */
    public void addOrderCount(int quantity) {
        this.orderCount += quantity;
    }
}
