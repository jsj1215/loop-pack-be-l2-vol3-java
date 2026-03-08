package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("Product 도메인 모델 단위 테스트")
class ProductTest {

    private Brand createBrandWithId(Long id) {
        Brand brand = new Brand("나이키", "스포츠 브랜드");
        brand.changeStatus(BrandStatus.ACTIVE);
        ReflectionTestUtils.setField(brand, "id", id);
        return brand;
    }

    private Product createProductWithId(Long id, Brand brand, String name, int price, int supplyPrice,
                                         int discountPrice, int shippingFee, int likeCount,
                                         String description, MarginType marginType,
                                         ProductStatus status, String displayYn,
                                         List<ProductOption> options) {
        Product product = new Product(brand, name, price, supplyPrice, discountPrice,
                shippingFee, description, marginType, status, displayYn, options);
        ReflectionTestUtils.setField(product, "id", id);
        ReflectionTestUtils.setField(product, "likeCount", likeCount);
        return product;
    }

    @Nested
    @DisplayName("상품을 생성할 때,")
    class Create {

        @Test
        @DisplayName("브랜드와 상품 정보로 생성하면 기본값이 설정된다.")
        void defaultValuesAreSet_whenCreated() {
            // given
            Brand brand = createBrandWithId(1L);
            List<ProductOption> options = List.of(
                    new ProductOption(null, "M 사이즈", 100)
            );

            // when
            Product product = new Product(brand, "에어맥스", 150000, 120000, 10000,
                    3000, "나이키 에어맥스", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y", options);

            // then
            assertAll(
                    () -> assertThat(product.getBrand()).isEqualTo(brand),
                    () -> assertThat(product.getName()).isEqualTo("에어맥스"),
                    () -> assertThat(product.getPrice()).isEqualTo(150000),
                    () -> assertThat(product.getSupplyPrice()).isEqualTo(120000),
                    () -> assertThat(product.getDiscountPrice()).isEqualTo(10000),
                    () -> assertThat(product.getShippingFee()).isEqualTo(3000),
                    () -> assertThat(product.getLikeCount()).isEqualTo(0),
                    () -> assertThat(product.getDescription()).isEqualTo("나이키 에어맥스"),
                    () -> assertThat(product.getMarginType()).isEqualTo(MarginType.AMOUNT),
                    () -> assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE),
                    () -> assertThat(product.getDisplayYn()).isEqualTo("Y"),
                    () -> assertThat(product.getOptions()).hasSize(1)
            );
        }
    }

    @Nested
    @DisplayName("공급가를 계산할 때,")
    class CalculateSupplyPrice {

        @Test
        @DisplayName("마진 타입이 AMOUNT이면 판매가에서 마진 금액을 뺀다.")
        void subtractsMarginAmount_whenAmountType() {
            // given
            int price = 100000;
            MarginType marginType = MarginType.AMOUNT;
            int marginValue = 30000;

            // when
            int supplyPrice = Product.calculateSupplyPrice(price, marginType, marginValue);

            // then
            assertThat(supplyPrice).isEqualTo(70000);
        }

        @Test
        @DisplayName("마진 타입이 RATE이면 판매가에서 비율만큼 뺀다.")
        void subtractsMarginRate_whenRateType() {
            // given
            int price = 100000;
            MarginType marginType = MarginType.RATE;
            int marginValue = 20;

            // when
            int supplyPrice = Product.calculateSupplyPrice(price, marginType, marginValue);

            // then
            assertThat(supplyPrice).isEqualTo(80000);
        }
    }

    @Nested
    @DisplayName("상품 정보를 수정할 때,")
    class UpdateInfo {

        @Test
        @DisplayName("변경 가능한 필드들이 모두 수정된다.")
        void updatesAllMutableFields() {
            // given
            Product product = createProductWithId(1L, createBrandWithId(1L), "에어맥스", 150000, 120000, 10000,
                    3000, 5, "설명", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y", List.of());

            List<ProductOption> newOptions = List.of(
                    new ProductOption(1L, "L 사이즈", 200)
            );

            // when
            product.updateInfo("에어포스", 180000, 140000, 15000,
                    2500, "새로운 설명", ProductStatus.SOLD_OUT, "N", newOptions);

            // then
            assertAll(
                    () -> assertThat(product.getName()).isEqualTo("에어포스"),
                    () -> assertThat(product.getPrice()).isEqualTo(180000),
                    () -> assertThat(product.getSupplyPrice()).isEqualTo(140000),
                    () -> assertThat(product.getDiscountPrice()).isEqualTo(15000),
                    () -> assertThat(product.getShippingFee()).isEqualTo(2500),
                    () -> assertThat(product.getDescription()).isEqualTo("새로운 설명"),
                    () -> assertThat(product.getStatus()).isEqualTo(ProductStatus.SOLD_OUT),
                    () -> assertThat(product.getDisplayYn()).isEqualTo("N"),
                    () -> assertThat(product.getOptions()).hasSize(1)
            );
        }
    }
}
