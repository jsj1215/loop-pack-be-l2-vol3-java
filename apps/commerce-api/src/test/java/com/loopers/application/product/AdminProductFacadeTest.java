package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.cart.CartService;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminProductFacade 단위 테스트")
class AdminProductFacadeTest {

    @Mock
    private ProductService productService;

    @Mock
    private BrandService brandService;

    @Mock
    private CartService cartService;

    @InjectMocks
    private AdminProductFacade adminProductFacade;

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
    @DisplayName("상품 생성을 할 때,")
    class CreateProduct {

        @Test
        @DisplayName("BrandService에서 브랜드를 찾고 ProductService에 등록한다.")
        void createProduct_callsBrandAndProductService() {
            // given
            Brand brand = createBrandWithId(1L);
            when(brandService.findById(1L)).thenReturn(brand);

            Product product = createProductWithId(1L, brand);
            when(productService.register(eq(brand), anyString(), anyInt(), any(MarginType.class), anyInt(), anyInt(), anyInt(), anyString(), anyList())).thenReturn(product);

            // when
            ProductDetailInfo info = adminProductFacade.createProduct(1L, "에어맥스", 100000, MarginType.AMOUNT, 20000, 10000, 3000, "설명", List.of(new ProductOption(null, "M사이즈", 100)));

            // then
            assertThat(info.name()).isEqualTo("에어맥스");
            verify(brandService).findById(1L);
        }
    }

    @Nested
    @DisplayName("상품 삭제를 할 때,")
    class DeleteProduct {

        @Test
        @DisplayName("CartService로 장바구니 삭제 후 ProductService로 상품을 삭제한다.")
        void deletesCartThenProduct() {
            // given
            when(productService.findOptionIdsByProductId(1L)).thenReturn(List.of(10L, 20L));

            // when
            adminProductFacade.deleteProduct(1L);

            // then
            verify(cartService).deleteByProductOptionIds(List.of(10L, 20L));
            verify(productService).softDelete(1L);
        }
    }
}
