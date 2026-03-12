package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;

    public Product register(Brand brand, String name, int price, MarginType marginType, int marginValue,
                            int discountPrice, int shippingFee, String description,
                            List<ProductOption> options) {
        if (productRepository.existsByBrandIdAndName(brand.getId(), name)) {
            throw new CoreException(ErrorType.CONFLICT, "같은 브랜드 내 동일한 상품명이 존재합니다.");
        }

        int supplyPrice = Product.calculateSupplyPrice(price, marginType, marginValue);

        Product product = new Product(brand, name, price, supplyPrice, discountPrice,
                shippingFee, description, marginType, ProductStatus.ON_SALE, "Y", options);

        return productRepository.save(product);
    }

    public Product findById(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
    }

    /**
     * Product 엔티티만 조회한다 (옵션은 로드하지 않음).
     * 비관적 락으로 ProductOption을 조회하기 전에 사용하여 1차 캐시 오염을 방지한다.
     */
    public Product findProductOnly(Long productId) {
        return productRepository.findProductOnly(productId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
    }

    public ProductOption findOptionById(Long optionId) {
        return productRepository.findOptionById(optionId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품 옵션을 찾을 수 없습니다."));
    }

    /**
     * 비정규화(Product.likeCount) 기반 상품 검색.
     * 좋아요순 정렬 시 Product 테이블의 like_count 컬럼을 직접 사용한다.
     */
    public Page<Product> search(ProductSearchCondition condition, Pageable pageable) {
        return productRepository.search(condition, pageable);
    }

    /**
     * [TEST용] Materialized View(product_like_summary) 기반 상품 검색.
     * 좋아요순 정렬 시 summary 테이블의 like_count를 LEFT JOIN으로 조회한다.
     * 비정규화 방식 대비 Product 테이블에 쓰기 부하가 없으나, 갱신 주기만큼 지연이 발생한다.
     */
    public Page<Product> searchWithMaterializedView(ProductSearchCondition condition, Pageable pageable) {
        return productRepository.searchWithMaterializedView(condition, pageable);
    }

    public Page<Product> adminSearch(AdminProductSearchCondition condition, Pageable pageable) {
        return productRepository.adminSearch(condition, pageable);
    }

    public Product update(Long productId, String name, int price, int supplyPrice, int discountPrice,
                          int shippingFee, String description, ProductStatus status,
                          String displayYn, List<ProductOption> options) {
        Product product = findById(productId);

        if (productRepository.existsByBrandIdAndNameAndIdNot(product.getBrand().getId(), name, productId)) {
            throw new CoreException(ErrorType.CONFLICT, "같은 브랜드 내 동일한 상품명이 존재합니다.");
        }

        product.updateInfo(name, price, supplyPrice, discountPrice, shippingFee,
                description, status, displayYn, options);

        return productRepository.save(product);
    }

    public void softDelete(Long productId) {
        findById(productId);
        productRepository.softDelete(productId);
    }

    public void softDeleteByBrandId(Long brandId) {
        productRepository.softDeleteByBrandId(brandId);
    }

    public List<Long> findOptionIdsByProductId(Long productId) {
        return productRepository.findOptionIdsByProductId(productId);
    }

    public List<Long> findOptionIdsByBrandId(Long brandId) {
        return productRepository.findOptionIdsByBrandId(brandId);
    }

    public void incrementLikeCount(Long productId) {
        productRepository.incrementLikeCount(productId);
    }

    public void decrementLikeCount(Long productId) {
        int updatedRows = productRepository.decrementLikeCount(productId);
        if (updatedRows == 0) {
            log.warn("좋아요 수 감소 실패: productId={}, likeCount가 이미 0입니다.", productId);
        }
    }

    /**
     * 재고를 차감하고 차감된 옵션을 반환한다.
     * 반드시 상위 레이어(@Transactional)의 트랜잭션 내에서 호출되어야 한다.
     */
    public ProductOption deductStock(Long optionId, int quantity) {
        ProductOption option = productRepository.findOptionByIdWithLock(optionId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품 옵션을 찾을 수 없습니다."));
        option.deductStock(quantity);
        productRepository.saveOption(option);
        return option;
    }

    /**
     * 재고를 복원한다.
     * 반드시 상위 레이어(@Transactional)의 트랜잭션 내에서 호출되어야 한다.
     */
    public void restoreStock(Long optionId, int quantity) {
        ProductOption option = productRepository.findOptionByIdWithLock(optionId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품 옵션을 찾을 수 없습니다."));
        option.restoreStock(quantity);
        productRepository.saveOption(option);
    }
}
