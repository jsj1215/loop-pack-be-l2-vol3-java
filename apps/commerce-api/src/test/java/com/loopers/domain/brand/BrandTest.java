package com.loopers.domain.brand;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("Brand 도메인 모델 단위 테스트")
class BrandTest {

    @Nested
    @DisplayName("브랜드를 생성할 때,")
    class Create {

        @Test
        @DisplayName("이름과 설명으로 생성하면 기본 상태는 PENDING이다.")
        void defaultStatusIsPending_whenCreated() {
            // given
            String name = "나이키";
            String description = "스포츠 브랜드";

            // when
            Brand brand = new Brand(name, description);

            // then
            assertAll(
                    () -> assertThat(brand.getName()).isEqualTo("나이키"),
                    () -> assertThat(brand.getDescription()).isEqualTo("스포츠 브랜드"),
                    () -> assertThat(brand.getStatus()).isEqualTo(BrandStatus.PENDING));
        }
    }

    @Nested
    @DisplayName("브랜드 정보를 수정할 때,")
    class UpdateInfo {

        @Test
        @DisplayName("이름, 설명, 상태를 변경할 수 있다.")
        void updatesAllFields() {
            // given
            Brand brand = new Brand("나이키", "스포츠");

            // when
            brand.updateInfo("아디다스", "스포츠 브랜드", BrandStatus.ACTIVE);

            // then
            assertAll(
                    () -> assertThat(brand.getName()).isEqualTo("아디다스"),
                    () -> assertThat(brand.getDescription()).isEqualTo("스포츠 브랜드"),
                    () -> assertThat(brand.getStatus()).isEqualTo(BrandStatus.ACTIVE));
        }
    }

    @Nested
    @DisplayName("브랜드 상태를 변경할 때,")
    class ChangeStatus {

        @Test
        @DisplayName("상태를 변경할 수 있다.")
        void changesStatus() {
            // given
            Brand brand = new Brand("나이키", "스포츠");

            // when
            brand.changeStatus(BrandStatus.ACTIVE);

            // then
            assertThat(brand.getStatus()).isEqualTo(BrandStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("브랜드 활성 여부를 확인할 때,")
    class IsActive {

        @Test
        @DisplayName("ACTIVE 상태이면 true를 반환한다.")
        void returnsTrue_whenActive() {
            // given
            Brand brand = new Brand("나이키", "스포츠");
            brand.changeStatus(BrandStatus.ACTIVE);

            // when & then
            assertThat(brand.isActive()).isTrue();
        }

        @Test
        @DisplayName("PENDING 상태이면 false를 반환한다.")
        void returnsFalse_whenPending() {
            // given
            Brand brand = new Brand("나이키", "스포츠");

            // when & then
            assertThat(brand.isActive()).isFalse();
        }
    }
}
