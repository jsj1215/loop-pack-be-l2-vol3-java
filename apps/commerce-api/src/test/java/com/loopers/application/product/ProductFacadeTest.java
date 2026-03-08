package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductSearchCondition;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductFacade 단위 테스트")
class ProductFacadeTest {

    @Mock
    private ProductService productService;

    @Mock
    private LikeService likeService;

    @InjectMocks
    private ProductFacade productFacade;

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
    @DisplayName("상품 목록 조회를 할 때,")
    class GetProducts {

        @Test
        @DisplayName("ProductService에서 조회한 결과를 ProductInfo로 변환한다.")
        void returnsProductInfoPage() {
            // given
            Brand brand = createBrandWithId(1L);
            Product product = createProductWithId(1L, brand);

            ProductSearchCondition condition = ProductSearchCondition.of(null, null, null);
            Pageable pageable = PageRequest.of(0, 10);
            when(productService.search(condition, pageable)).thenReturn(new PageImpl<>(List.of(product)));

            // when
            Page<ProductInfo> result = productFacade.getProducts(condition, pageable);

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).name()).isEqualTo("에어맥스");
        }
    }

    @Nested
    @DisplayName("좋아요를 할 때,")
    class LikeProduct {

        @Test
        @DisplayName("LikeService에 위임한다.")
        void delegatesToLikeService() {
            // when
            productFacade.like(1L, 1L);

            // then
            verify(likeService).like(1L, 1L);
        }
    }

    @Nested
    @DisplayName("좋아요 취소를 할 때,")
    class UnlikeProduct {

        @Test
        @DisplayName("LikeService에 위임한다.")
        void delegatesToLikeService() {
            // when
            productFacade.unlike(1L, 1L);

            // then
            verify(likeService).unlike(1L, 1L);
        }
    }
}
