package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.cart.CartService;
import com.loopers.domain.product.AdminProductSearchCondition;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductCacheStore;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class AdminProductFacade {

    private final ProductService productService;
    private final BrandService brandService;
    private final CartService cartService;
    private final ProductCacheStore productCacheStore;

    public Page<ProductInfo> getProducts(AdminProductSearchCondition condition, Pageable pageable) {
        return productService.adminSearch(condition, pageable).map(ProductInfo::from);
    }

    public ProductDetailInfo getProduct(Long productId) {
        Product product = productService.findById(productId);
        return ProductDetailInfo.from(product);
    }

    @Transactional
    public ProductDetailInfo createProduct(Long brandId, String name, int price, MarginType marginType,
                                           int marginValue, int discountPrice, int shippingFee,
                                           String description, List<ProductOption> options) {
        Brand brand = brandService.findById(brandId);
        Product product = productService.register(brand, name, price, marginType, marginValue,
                discountPrice, shippingFee, description, options);
        return ProductDetailInfo.from(product);
    }

    @Transactional
    public ProductDetailInfo updateProduct(Long productId, String name, int price, int supplyPrice,
                                           int discountPrice, int shippingFee, String description,
                                           ProductStatus status, String displayYn,
                                           List<ProductOption> options) {
        Product product = productService.update(productId, name, price, supplyPrice, discountPrice,
                shippingFee, description, status, displayYn, options);

        // 상품 수정 시 상세 캐시를 명시적으로 삭제 -> 삭제 후 최초 사용자가 조회시 캐싱.
        productCacheStore.evictDetail(productId);
        return ProductDetailInfo.from(product);
    }

    @Transactional
    public void deleteProduct(Long productId) {
        List<Long> optionIds = productService.findOptionIdsByProductId(productId);
        cartService.deleteByProductOptionIds(optionIds);
        productService.softDelete(productId);

        // 삭제된 상품의 상세 캐시를 제거하여 삭제 후에도 캐시된 데이터가 반환되지 않도록 한다.
        productCacheStore.evictDetail(productId);
    }
}
