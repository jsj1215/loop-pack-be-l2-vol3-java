package com.loopers.domain.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProductMetrics 도메인 엔티티 테스트")
class ProductMetricsTest {

    @Test
    @DisplayName("생성 시 모든 카운트가 0으로 초기화된다")
    void createWithZeroCounts() {
        // given & when
        ProductMetrics metrics = new ProductMetrics(1L);

        // then
        assertThat(metrics.getProductId()).isEqualTo(1L);
        assertThat(metrics.getLikeCount()).isZero();
        assertThat(metrics.getViewCount()).isZero();
        assertThat(metrics.getOrderCount()).isZero();
    }

    @Test
    @DisplayName("좋아요 수 증가/감소가 정상 동작한다")
    void incrementAndDecrementLikeCount() {
        // given
        ProductMetrics metrics = new ProductMetrics(1L);

        // when
        metrics.incrementLikeCount();
        metrics.incrementLikeCount();
        metrics.decrementLikeCount();

        // then
        assertThat(metrics.getLikeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("좋아요 수가 0 미만으로 내려가지 않는다")
    void likeCountDoesNotGoBelowZero() {
        // given
        ProductMetrics metrics = new ProductMetrics(1L);

        // when
        metrics.decrementLikeCount();

        // then
        assertThat(metrics.getLikeCount()).isZero();
    }

    @Test
    @DisplayName("조회수 증가가 정상 동작한다")
    void incrementViewCount() {
        // given
        ProductMetrics metrics = new ProductMetrics(1L);

        // when
        metrics.incrementViewCount();
        metrics.incrementViewCount();

        // then
        assertThat(metrics.getViewCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("판매 수량이 주어진 quantity만큼 증가한다")
    void addOrderCount() {
        // given
        ProductMetrics metrics = new ProductMetrics(1L);

        // when
        metrics.addOrderCount(3);
        metrics.addOrderCount(2);

        // then
        assertThat(metrics.getOrderCount()).isEqualTo(5);
    }
}
