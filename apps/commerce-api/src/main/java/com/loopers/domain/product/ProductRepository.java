package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(Long id);

    Optional<ProductOption> findOptionById(Long optionId);

    boolean existsByBrandIdAndName(Long brandId, String name);

    boolean existsByBrandIdAndNameAndIdNot(Long brandId, String name, Long id);

    Page<Product> search(ProductSearchCondition condition, Pageable pageable);

    Page<Product> adminSearch(AdminProductSearchCondition condition, Pageable pageable);

    void softDelete(Long productId);

    void softDeleteByBrandId(Long brandId);

    List<Long> findOptionIdsByProductId(Long productId);

    List<Long> findOptionIdsByBrandId(Long brandId);

    void saveOption(ProductOption option);
}
