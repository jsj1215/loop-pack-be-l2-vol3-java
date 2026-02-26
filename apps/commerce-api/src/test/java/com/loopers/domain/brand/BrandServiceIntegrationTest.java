package com.loopers.domain.brand;

import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [통합 테스트 - Service Integration]
 *
 * 테스트 대상: BrandService (Domain Layer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Service → Repository → Database
 *
 * 사용 라이브러리:
 * - JUnit 5 (org.junit.jupiter)
 * - Spring Boot Test (org.springframework.boot.test.context)
 * - Testcontainers (org.testcontainers) - testFixtures 모듈에서 제공
 * - AssertJ (org.assertj.core.api)
 */
@SpringBootTest
@Transactional
class BrandServiceIntegrationTest {

    @Autowired
    private BrandService brandService;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드를 등록할 때,")
    @Nested
    class Register {

        @Test
        @DisplayName("유효한 이름으로 등록하면, Brand가 생성된다.")
        void createsBrand_whenValidName() {
            // given
            String name = "나이키";
            String description = "스포츠 브랜드";

            // when
            Brand brand = brandService.register(name, description);

            // then
            assertAll(
                    () -> assertThat(brand.getId()).isNotNull(),
                    () -> assertThat(brand.getName()).isEqualTo("나이키"),
                    () -> assertThat(brand.getDescription()).isEqualTo("스포츠 브랜드"),
                    () -> assertThat(brand.getStatus()).isEqualTo(BrandStatus.PENDING)
            );
        }

        @Test
        @DisplayName("중복 브랜드명이면, CONFLICT 예외가 발생한다.")
        void throwsException_whenDuplicateName() {
            // given
            brandService.register("나이키", "스포츠 브랜드");

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> brandService.register("나이키", "다른 설명"));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    () -> assertThat(exception.getMessage()).contains("이미 존재하는 브랜드명")
            );
        }
    }

    @DisplayName("브랜드를 ID로 조회할 때,")
    @Nested
    class FindById {

        @Test
        @DisplayName("존재하는 브랜드를 조회하면, 성공한다.")
        void returnsBrand_whenExists() {
            // given
            Brand savedBrand = brandService.register("나이키", "스포츠 브랜드");

            // when
            Brand brand = brandService.findById(savedBrand.getId());

            // then
            assertAll(
                    () -> assertThat(brand.getId()).isEqualTo(savedBrand.getId()),
                    () -> assertThat(brand.getName()).isEqualTo("나이키"),
                    () -> assertThat(brand.getDescription()).isEqualTo("스포츠 브랜드")
            );
        }

        @Test
        @DisplayName("존재하지 않는 브랜드를 조회하면, NOT_FOUND 예외가 발생한다.")
        void throwsException_whenNotExists() {
            // given
            Long nonExistentId = 999L;

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> brandService.findById(nonExistentId));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("활성 브랜드를 조회할 때,")
    @Nested
    class FindActiveBrand {

        @Test
        @DisplayName("ACTIVE 브랜드를 조회하면, 성공한다.")
        void returnsActiveBrand_whenActive() {
            // given
            Brand savedBrand = brandService.register("나이키", "스포츠 브랜드");
            brandService.update(savedBrand.getId(), "나이키", "스포츠 브랜드", BrandStatus.ACTIVE);

            // when
            Brand brand = brandService.findActiveBrand(savedBrand.getId());

            // then
            assertAll(
                    () -> assertThat(brand.getId()).isEqualTo(savedBrand.getId()),
                    () -> assertThat(brand.getStatus()).isEqualTo(BrandStatus.ACTIVE)
            );
        }

        @Test
        @DisplayName("PENDING 브랜드를 조회하면, NOT_FOUND 예외가 발생한다.")
        void throwsException_whenPending() {
            // given
            Brand savedBrand = brandService.register("나이키", "스포츠 브랜드");

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> brandService.findActiveBrand(savedBrand.getId()));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("브랜드를 수정할 때,")
    @Nested
    class Update {

        @Test
        @DisplayName("유효한 정보로 수정하면, 성공한다.")
        void updatesBrand_whenValidInput() {
            // given
            Brand savedBrand = brandService.register("나이키", "스포츠 브랜드");

            // when
            Brand updatedBrand = brandService.update(
                    savedBrand.getId(), "나이키 코리아", "한국 나이키", BrandStatus.ACTIVE);

            // then
            assertAll(
                    () -> assertThat(updatedBrand.getName()).isEqualTo("나이키 코리아"),
                    () -> assertThat(updatedBrand.getDescription()).isEqualTo("한국 나이키"),
                    () -> assertThat(updatedBrand.getStatus()).isEqualTo(BrandStatus.ACTIVE)
            );
        }

        @Test
        @DisplayName("다른 브랜드와 이름이 중복되면, CONFLICT 예외가 발생한다.")
        void throwsException_whenNameConflictsWithOtherBrand() {
            // given
            brandService.register("나이키", "스포츠 브랜드");
            Brand adidasBrand = brandService.register("아디다스", "독일 스포츠 브랜드");

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> brandService.update(adidasBrand.getId(), "나이키", "변경 설명", BrandStatus.ACTIVE));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    () -> assertThat(exception.getMessage()).contains("이미 존재하는 브랜드명")
            );
        }
    }

    @DisplayName("브랜드를 소프트 삭제할 때,")
    @Nested
    class SoftDelete {

        @Test
        @DisplayName("존재하는 브랜드를 삭제하면, 조회되지 않는다.")
        void softDeletesBrand_whenExists() {
            // given
            Brand savedBrand = brandService.register("나이키", "스포츠 브랜드");

            // when
            brandService.softDelete(savedBrand.getId());

            // then
            CoreException exception = assertThrows(CoreException.class,
                    () -> brandService.findById(savedBrand.getId()));
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
