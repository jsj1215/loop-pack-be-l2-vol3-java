package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByBrand_IdAndNameAndDeletedAtIsNull(Long brandId, String name);

    boolean existsByBrand_IdAndNameAndIdNotAndDeletedAtIsNull(Long brandId, String name, Long id);
}
