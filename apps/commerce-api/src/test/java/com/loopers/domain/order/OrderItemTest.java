package com.loopers.domain.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("OrderItem 도메인 모델 단위 테스트")
class OrderItemTest {

    private Brand createBrandWithId(Long id, String name) {
        Brand brand = new Brand(name, "스포츠");
        brand.changeStatus(BrandStatus.ACTIVE);
        ReflectionTestUtils.setField(brand, "id", id);
        return brand;
    }

    private Product createProductWithId(Long id, Brand brand) {
        Product product = new Product(brand, "운동화", 50000, 40000, 5000, 3000,
                "설명", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y", List.of());
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private ProductOption createProductOptionWithId(Long id, Long productId, String optionName, int stockQuantity) {
        ProductOption option = new ProductOption(productId, optionName, stockQuantity);
        ReflectionTestUtils.setField(option, "id", id);
        return option;
    }

    private OrderItem createOrderItemWithId(Long id, Long productId, Long brandId,
                                             String productName, String optionName, String brandName,
                                             int price, int supplyPrice, int shippingFee, int quantity) {
        Brand brand = createBrandWithId(brandId, brandName);
        Product product = new Product(brand, productName, price, supplyPrice, 0, shippingFee,
                "", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y", List.of());
        ReflectionTestUtils.setField(product, "id", productId);

        ProductOption option = createProductOptionWithId(1L, productId, optionName, 100);

        OrderItem orderItem = OrderItem.createSnapshot(product, option, quantity);
        ReflectionTestUtils.setField(orderItem, "id", id);
        return orderItem;
    }

    @Nested
    @DisplayName("상품 스냅샷을 생성할 때,")
    class CreateSnapshot {

        @Test
        @DisplayName("상품과 옵션 정보가 올바르게 복사된다.")
        void copiesProductAndOptionFieldsCorrectly() {
            // given
            Brand brand = createBrandWithId(1L, "나이키");
            Product product = createProductWithId(1L, brand);
            ProductOption option = createProductOptionWithId(1L, 1L, "270mm", 100);
            int quantity = 2;

            // when
            OrderItem orderItem = OrderItem.createSnapshot(product, option, quantity);

            // then
            assertAll(
                    () -> assertThat(orderItem.getProductId()).isEqualTo(1L),
                    () -> assertThat(orderItem.getBrandId()).isEqualTo(1L),
                    () -> assertThat(orderItem.getProductName()).isEqualTo("운동화"),
                    () -> assertThat(orderItem.getOptionName()).isEqualTo("270mm"),
                    () -> assertThat(orderItem.getBrandName()).isEqualTo("나이키"),
                    () -> assertThat(orderItem.getPrice()).isEqualTo(50000),
                    () -> assertThat(orderItem.getSupplyPrice()).isEqualTo(40000),
                    () -> assertThat(orderItem.getShippingFee()).isEqualTo(3000),
                    () -> assertThat(orderItem.getQuantity()).isEqualTo(2)
            );
        }
    }

    @Nested
    @DisplayName("소계를 계산할 때,")
    class GetSubtotal {

        @Test
        @DisplayName("가격과 수량의 곱을 반환한다.")
        void returnsPriceMultipliedByQuantity() {
            // given
            OrderItem orderItem = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 3);

            // when
            int subtotal = orderItem.getSubtotal();

            // then
            assertThat(subtotal).isEqualTo(150000);
        }
    }
}
