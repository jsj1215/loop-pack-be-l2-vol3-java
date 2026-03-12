package com.loopers.config;

import com.loopers.application.product.ProductDetailInfo;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.product.ProductInfo;
import com.loopers.domain.product.ProductSearchCondition;
import com.loopers.domain.product.ProductSortType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 기동 시 자주 조회되는 상품 데이터를 캐시에 미리 적재한다.
 *
 * - 상품 목록: 기본 정렬(LATEST) 0~2페이지
 * - 상품 상세: 목록 첫 페이지에 노출된 상품들의 상세 정보
 *
 * Cache-Aside 로직은 ProductFacade가 담당하므로,
 * 웜업은 Facade 메서드를 호출하여 자연스럽게 캐시를 적재한다.
 * 웜업 실패 시에도 서비스 기동에는 영향을 주지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ProductCacheWarmUp {

    private static final int WARM_UP_MAX_PAGE = 2;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ProductFacade productFacade;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        log.info("[CacheWarmUp] 상품 캐시 웜업 시작");

        int listCount = warmUpSearchCache();
        int detailCount = warmUpDetailCache();

        log.info("[CacheWarmUp] 상품 캐시 웜업 완료 - 목록 {}페이지, 상세 {}건", listCount, detailCount);
    }

    private int warmUpSearchCache() {
        ProductSearchCondition defaultCondition = ProductSearchCondition.of(null, ProductSortType.LATEST, null);
        int warmedPages = 0;

        for (int page = 0; page <= WARM_UP_MAX_PAGE; page++) {
            try {
                Pageable pageable = PageRequest.of(page, DEFAULT_PAGE_SIZE);
                productFacade.getProducts(defaultCondition, pageable);
                warmedPages++;
            } catch (Exception e) {
                log.warn("[CacheWarmUp] 상품 목록 캐시 웜업 실패: page={}", page, e);
            }
        }
        return warmedPages;
    }

    private int warmUpDetailCache() {
        ProductSearchCondition defaultCondition = ProductSearchCondition.of(null, ProductSortType.LATEST, null);
        int warmedCount = 0;

        try {
            Pageable pageable = PageRequest.of(0, DEFAULT_PAGE_SIZE);
            Page<ProductInfo> products = productFacade.getProducts(defaultCondition, pageable);

            for (ProductInfo productInfo : products.getContent()) {
                try {
                    productFacade.getProduct(productInfo.id());
                    warmedCount++;
                } catch (Exception e) {
                    log.warn("[CacheWarmUp] 상품 상세 캐시 웜업 실패: productId={}", productInfo.id(), e);
                }
            }
        } catch (Exception e) {
            log.warn("[CacheWarmUp] 상품 상세 캐시 웜업 대상 조회 실패", e);
        }
        return warmedCount;
    }
}
