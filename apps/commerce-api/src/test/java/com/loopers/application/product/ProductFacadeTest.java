package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.event.ProductLikedEvent;
import com.loopers.domain.event.ProductViewedEvent;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductCacheStore;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductSearchCondition;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductFacade 단위 테스트")
class ProductFacadeTest {

    @Mock
    private ProductService productService;

    @Mock
    private LikeService likeService;

    @Mock
    private ProductCacheStore redisCacheStore;

    @Mock
    private ProductCacheStore localCacheStore;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ProductFacade createFacade() {
        return new ProductFacade(productService, likeService, redisCacheStore, localCacheStore,
                eventPublisher);
    }

    private Brand createBrandWithId(Long id) {
        Brand brand = new Brand("나이키", "스포츠");
        brand.changeStatus(BrandStatus.ACTIVE);
        ReflectionTestUtils.setField(brand, "id", id);
        return brand;
    }

    private ProductOption createProductOptionWithId(Long id, Long productId) {
        ProductOption option = new ProductOption(productId, "M사이즈", 100);
        ReflectionTestUtils.setField(option, "id", id);
        return option;
    }

    private Product createProductWithId(Long id, Brand brand) {
        Product product = new Product(brand, "에어맥스", 100000, 80000, 10000, 3000,
                "설명", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y",
                List.of(createProductOptionWithId(1L, id)));
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    @Nested
    @DisplayName("상품 목록 조회(Redis)를 할 때,")
    class GetProducts {

        @Test
        @DisplayName("캐시 히트 시 DB를 조회하지 않고 캐시된 결과를 반환한다.")
        void returnsCachedResult_whenCacheHit() {
            // given
            ProductFacade facade = createFacade();
            Brand brand = createBrandWithId(1L);
            Product product = createProductWithId(1L, brand);

            ProductSearchCondition condition = ProductSearchCondition.of(null, null, null);
            Pageable pageable = PageRequest.of(0, 10);
            Page<Product> cachedPage = new PageImpl<>(List.of(product));

            when(redisCacheStore.getSearch(condition, pageable)).thenReturn(Optional.of(cachedPage));

            // when
            Page<ProductInfo> result = facade.getProducts(condition, pageable);

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).name()).isEqualTo("에어맥스");
            verify(productService, never()).search(condition, pageable);
        }

        @Test
        @DisplayName("캐시 미스 시 DB에서 조회하고 캐시에 저장한다.")
        void queriesDbAndCaches_whenCacheMiss() {
            // given
            ProductFacade facade = createFacade();
            Brand brand = createBrandWithId(1L);
            Product product = createProductWithId(1L, brand);

            ProductSearchCondition condition = ProductSearchCondition.of(null, null, null);
            Pageable pageable = PageRequest.of(0, 10);
            Page<Product> dbPage = new PageImpl<>(List.of(product));

            when(redisCacheStore.getSearch(condition, pageable)).thenReturn(Optional.empty());
            when(productService.search(condition, pageable)).thenReturn(dbPage);

            // when
            Page<ProductInfo> result = facade.getProducts(condition, pageable);

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).name()).isEqualTo("에어맥스");
            verify(productService).search(condition, pageable);
            verify(redisCacheStore).setSearch(condition, pageable, dbPage);
        }
    }

    @Nested
    @DisplayName("상품 상세 조회(Redis)를 할 때,")
    class GetProduct {

        @Test
        @DisplayName("캐시 히트 시 DB를 조회하지 않고 캐시된 결과를 반환하며, ProductViewedEvent를 발행한다.")
        void returnsCachedResult_whenCacheHit() {
            // given
            ProductFacade facade = createFacade();
            Brand brand = createBrandWithId(1L);
            Product product = createProductWithId(1L, brand);

            when(redisCacheStore.getDetail(1L)).thenReturn(Optional.of(product));

            // when
            ProductDetailInfo result = facade.getProduct(1L, 100L);

            // then
            assertThat(result.name()).isEqualTo("에어맥스");
            verify(productService, never()).findById(1L);
            verify(eventPublisher).publishEvent(new ProductViewedEvent(1L, 100L));
        }

        @Test
        @DisplayName("캐시 미스 시 DB에서 조회하고 캐시에 저장하며, ProductViewedEvent를 발행한다.")
        void queriesDbAndCaches_whenCacheMiss() {
            // given
            ProductFacade facade = createFacade();
            Brand brand = createBrandWithId(1L);
            Product product = createProductWithId(1L, brand);

            when(redisCacheStore.getDetail(1L)).thenReturn(Optional.empty());
            when(productService.findById(1L)).thenReturn(product);

            // when
            ProductDetailInfo result = facade.getProduct(1L, null);

            // then
            assertThat(result.name()).isEqualTo("에어맥스");
            verify(productService).findById(1L);
            verify(redisCacheStore).setDetail(1L, product);
            verify(eventPublisher).publishEvent(new ProductViewedEvent(1L, null));
        }
    }

    @Nested
    @DisplayName("상품 목록 조회(로컬 캐시)를 할 때,")
    class GetProductsWithLocalCache {

        @Test
        @DisplayName("로컬 캐시 히트 시 DB를 조회하지 않고 캐시된 결과를 반환한다.")
        void returnsCachedResult_whenLocalCacheHit() {
            // given
            ProductFacade facade = createFacade();
            Brand brand = createBrandWithId(1L);
            Product product = createProductWithId(1L, brand);

            ProductSearchCondition condition = ProductSearchCondition.of(null, null, null);
            Pageable pageable = PageRequest.of(0, 10);
            Page<Product> cachedPage = new PageImpl<>(List.of(product));

            when(localCacheStore.getSearch(condition, pageable)).thenReturn(Optional.of(cachedPage));

            // when
            Page<ProductInfo> result = facade.getProductsWithLocalCache(condition, pageable);

            // then
            assertThat(result.getContent()).hasSize(1);
            verify(productService, never()).search(condition, pageable);
        }
    }

    @Nested
    @DisplayName("좋아요를 할 때,")
    class LikeProduct {

        @Test
        @DisplayName("상품 존재 확인 후 LikeService에 위임하고, 상태가 변경되면 ProductLikedEvent를 발행한다.")
        void delegatesToLikeService_andPublishesEvent_whenChanged() {
            // given
            ProductFacade facade = createFacade();
            Brand brand = createBrandWithId(1L);
            Product product = createProductWithId(1L, brand);

            when(productService.findById(1L)).thenReturn(product);
            when(likeService.like(1L, 1L)).thenReturn(true);

            // when
            facade.like(1L, 1L);

            // then
            verify(productService).findById(1L);
            verify(likeService).like(1L, 1L);
            verify(eventPublisher).publishEvent(new ProductLikedEvent(1L, 1L, true));
        }

        @Test
        @DisplayName("이미 좋아요 상태면 이벤트를 발행하지 않는다. (멱등성)")
        void doesNotPublishEvent_whenAlreadyLiked() {
            // given
            ProductFacade facade = createFacade();
            Brand brand = createBrandWithId(1L);
            Product product = createProductWithId(1L, brand);

            when(productService.findById(1L)).thenReturn(product);
            when(likeService.like(1L, 1L)).thenReturn(false);

            // when
            facade.like(1L, 1L);

            // then
            verify(eventPublisher, never()).publishEvent(any(ProductLikedEvent.class));
        }
    }

    @Nested
    @DisplayName("좋아요 취소를 할 때,")
    class UnlikeProduct {

        @Test
        @DisplayName("LikeService에 위임하고, 상태가 변경되면 ProductLikedEvent(liked=false)를 발행한다.")
        void delegatesToLikeService_andPublishesEvent_whenChanged() {
            // given
            ProductFacade facade = createFacade();

            when(likeService.unlike(1L, 1L)).thenReturn(true);

            // when
            facade.unlike(1L, 1L);

            // then
            verify(likeService).unlike(1L, 1L);
            verify(eventPublisher).publishEvent(new ProductLikedEvent(1L, 1L, false));
        }

        @Test
        @DisplayName("이미 취소 상태면 이벤트를 발행하지 않는다. (멱등성)")
        void doesNotPublishEvent_whenAlreadyUnliked() {
            // given
            ProductFacade facade = createFacade();

            when(likeService.unlike(1L, 1L)).thenReturn(false);

            // when
            facade.unlike(1L, 1L);

            // then
            verify(eventPublisher, never()).publishEvent(any(ProductLikedEvent.class));
        }
    }
}
