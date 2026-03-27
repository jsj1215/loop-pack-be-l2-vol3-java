package com.loopers.domain.metrics;

import java.util.Optional;

/**
 * 상품 메트릭 저장소 인터페이스 (DIP).
 */
public interface ProductMetricsRepository {

    /**
     * 상품 ID로 메트릭을 조회한다.
     *
     * @param productId 상품 ID
     * @return 메트릭 (없으면 empty)
     */
    Optional<ProductMetrics> findByProductId(Long productId);

    /**
     * 상품 메트릭을 저장/업데이트한다.
     *
     * @param productMetrics 저장할 메트릭
     * @return 저장된 메트릭
     */
    ProductMetrics save(ProductMetrics productMetrics);
}
