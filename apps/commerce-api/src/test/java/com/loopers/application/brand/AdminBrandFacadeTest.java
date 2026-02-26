package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.cart.CartService;
import com.loopers.domain.product.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminBrandFacade 단위 테스트")
class AdminBrandFacadeTest {

    @Mock
    private BrandService brandService;

    @Mock
    private ProductService productService;

    @Mock
    private CartService cartService;

    @InjectMocks
    private AdminBrandFacade adminBrandFacade;

    private Brand createBrandWithId(Long id, String name, String description, BrandStatus status) {
        Brand brand = new Brand(name, description);
        brand.changeStatus(status);
        ReflectionTestUtils.setField(brand, "id", id);
        return brand;
    }

    @Nested
    @DisplayName("브랜드 생성을 할 때,")
    class CreateBrand {

        @Test
        @DisplayName("BrandService를 호출하고 BrandInfo를 반환한다.")
        void returnsBrandInfo_whenCreated() {
            // given
            Brand brand = createBrandWithId(1L, "나이키", "스포츠 브랜드", BrandStatus.PENDING);
            when(brandService.register("나이키", "스포츠 브랜드")).thenReturn(brand);

            // when
            BrandInfo info = adminBrandFacade.createBrand("나이키", "스포츠 브랜드");

            // then
            assertThat(info.id()).isEqualTo(1L);
            assertThat(info.name()).isEqualTo("나이키");
        }
    }

    @Nested
    @DisplayName("브랜드 삭제를 할 때,")
    class DeleteBrand {

        @Test
        @DisplayName("CartService, ProductService, BrandService 순서로 삭제한다.")
        void deletesInCorrectOrder() {
            // given
            Brand brand = createBrandWithId(1L, "나이키", "스포츠 브랜드", BrandStatus.ACTIVE);
            when(brandService.findById(1L)).thenReturn(brand);

            // when
            adminBrandFacade.deleteBrand(1L);

            // then
            verify(cartService).deleteByBrandId(1L);
            verify(productService).softDeleteByBrandId(1L);
            verify(brandService).softDelete(1L);
        }
    }

    @Nested
    @DisplayName("브랜드 수정을 할 때,")
    class UpdateBrand {

        @Test
        @DisplayName("BrandService를 호출하고 BrandInfo를 반환한다.")
        void returnsBrandInfo_whenUpdated() {
            // given
            Brand brand = createBrandWithId(1L, "나이키 코리아", "수정됨", BrandStatus.ACTIVE);
            when(brandService.update(1L, "나이키 코리아", "수정됨", BrandStatus.ACTIVE)).thenReturn(brand);

            // when
            BrandInfo info = adminBrandFacade.updateBrand(1L, "나이키 코리아", "수정됨", BrandStatus.ACTIVE);

            // then
            assertThat(info.name()).isEqualTo("나이키 코리아");
        }
    }
}
