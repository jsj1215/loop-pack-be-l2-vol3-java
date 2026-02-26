package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductOptionJpaRepository extends JpaRepository<ProductOption, Long> {

    List<ProductOption> findAllByProductIdAndDeletedAtIsNull(Long productId);

    Optional<ProductOption> findByIdAndDeletedAtIsNull(Long id);

    List<ProductOption> findAllByProductIdInAndDeletedAtIsNull(List<Long> productIds);

    void deleteAllByProductId(Long productId);
}
