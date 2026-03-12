package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/**
 * 상품 캐시 저장소 Port.
 * Domain 레이어에서 캐시 조회/저장/삭제를 추상화하며,
 * 구체적인 캐시 기술(Redis, Caffeine 등)은 Infrastructure 구현체가 결정한다.
 */
public interface ProductCacheStore {

    Optional<Product> getDetail(Long productId);

    void setDetail(Long productId, Product product);

    void evictDetail(Long productId);

    /**
     * 검색 조건에 해당하는 캐시를 조회한다.
     * 캐싱 대상이 아닌 페이지이거나 캐시 미스인 경우 Optional.empty()를 반환한다.
     */
    Optional<Page<Product>> getSearch(ProductSearchCondition condition, Pageable pageable);

    /**
     * 검색 결과를 캐시에 저장한다.
     * 캐싱 대상이 아닌 페이지인 경우 저장하지 않는다.
     */
    void setSearch(ProductSearchCondition condition, Pageable pageable, Page<Product> products);
}
