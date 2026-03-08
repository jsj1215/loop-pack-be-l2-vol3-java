package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByBrand_IdAndNameAndDeletedAtIsNull(Long brandId, String name);

    boolean existsByBrand_IdAndNameAndIdNotAndDeletedAtIsNull(Long brandId, String name, Long id);

    @Modifying
    @Query("UPDATE Product p SET p.likeCount = p.likeCount + 1 WHERE p.id = :productId")
    int incrementLikeCount(@Param("productId") Long productId);

    @Modifying
    @Query("UPDATE Product p SET p.likeCount = p.likeCount - 1 WHERE p.id = :productId AND p.likeCount > 0")
    int decrementLikeCount(@Param("productId") Long productId);
}
