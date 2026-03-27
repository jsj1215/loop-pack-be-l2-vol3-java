package com.loopers.infrastructure.metrics;

import com.loopers.domain.metrics.ProductMetrics;
import org.springframework.data.jpa.repository.JpaRepository;

// 상품 메트릭스 JPA Repository
public interface ProductMetricsJpaRepository extends JpaRepository<ProductMetrics, Long> {
}
