package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductOption;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductOptionJpaRepository extends JpaRepository<ProductOption, Long> {

    List<ProductOption> findAllByProductIdAndDeletedAtIsNull(Long productId);

    Optional<ProductOption> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT po FROM ProductOption po WHERE po.id = :id AND po.deletedAt IS NULL")
    Optional<ProductOption> findByIdWithLockAndDeletedAtIsNull(@Param("id") Long id);

    List<ProductOption> findAllByProductIdInAndDeletedAtIsNull(List<Long> productIds);

    void deleteAllByProductId(Long productId);
}
