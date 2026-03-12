package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(Long id);

    Optional<Product> findProductOnly(Long id);

    Optional<ProductOption> findOptionById(Long optionId);

    Optional<ProductOption> findOptionByIdWithLock(Long optionId);

    boolean existsByBrandIdAndName(Long brandId, String name);

    boolean existsByBrandIdAndNameAndIdNot(Long brandId, String name, Long id);

    Page<Product> search(ProductSearchCondition condition, Pageable pageable);

    /**
     * Materialized View(product_like_summary) 기반 상품 검색.
     * 좋아요 수를 summary 테이블 JOIN으로 조회하며, 좋아요순 정렬 시 summary 테이블의 like_count를 사용한다.
     */
    Page<Product> searchWithMaterializedView(ProductSearchCondition condition, Pageable pageable);

    Page<Product> adminSearch(AdminProductSearchCondition condition, Pageable pageable);

    void softDelete(Long productId);

    void softDeleteByBrandId(Long brandId);

    List<Long> findOptionIdsByProductId(Long productId);

    List<Long> findOptionIdsByBrandId(Long brandId);

    void saveOption(ProductOption option);

    int incrementLikeCount(Long productId);

    int decrementLikeCount(Long productId);
}
