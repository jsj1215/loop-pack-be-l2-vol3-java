package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BrandFacade 단위 테스트")
class BrandFacadeTest {

    @Mock
    private BrandService brandService;

    @InjectMocks
    private BrandFacade brandFacade;

    private Brand createBrandWithId(Long id, String name, String description, BrandStatus status) {
        Brand brand = new Brand(name, description);
        brand.changeStatus(status);
        ReflectionTestUtils.setField(brand, "id", id);
        return brand;
    }

    @Nested
    @DisplayName("브랜드 조회를 할 때,")
    class GetBrand {

        @Test
        @DisplayName("활성 브랜드를 찾아 BrandInfo로 변환한다.")
        void returnsBrandInfo_whenActiveBrandExists() {
            // given
            Brand brand = createBrandWithId(1L, "나이키", "스포츠 브랜드", BrandStatus.ACTIVE);
            when(brandService.findActiveBrand(1L)).thenReturn(brand);

            // when
            BrandInfo info = brandFacade.getBrand(1L);

            // then
            assertThat(info.id()).isEqualTo(1L);
            assertThat(info.name()).isEqualTo("나이키");
        }

        @Test
        @DisplayName("비활성 브랜드면 예외가 전파된다.")
        void throwsException_whenBrandNotActive() {
            // given
            when(brandService.findActiveBrand(1L))
                .thenThrow(new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));

            // when & then
            assertThatThrownBy(() -> brandFacade.getBrand(1L))
                .isInstanceOf(CoreException.class);
        }
    }
}
