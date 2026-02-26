package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
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

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BrandService 단위 테스트")
class BrandServiceTest {

    @Mock
    private BrandRepository brandRepository;

    @InjectMocks
    private BrandService brandService;

    private Brand createBrandWithId(Long id, String name, String description, BrandStatus status) {
        Brand brand = new Brand(name, description);
        brand.changeStatus(status);
        ReflectionTestUtils.setField(brand, "id", id);
        return brand;
    }

    @Nested
    @DisplayName("브랜드를 등록할 때,")
    class Register {

        @Test
        @DisplayName("유효한 정보로 등록하면 브랜드가 생성된다.")
        void createsBrand_whenValidInfo() {
            // given
            when(brandRepository.existsByName("나이키")).thenReturn(false);
            when(brandRepository.save(any(Brand.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            Brand brand = brandService.register("나이키", "스포츠 브랜드");

            // then
            assertAll(
                    () -> assertThat(brand.getName()).isEqualTo("나이키"),
                    () -> assertThat(brand.getStatus()).isEqualTo(BrandStatus.PENDING),
                    () -> verify(brandRepository, times(1)).save(any(Brand.class)));
        }

        @Test
        @DisplayName("중복된 브랜드명이면 CONFLICT 예외가 발생한다.")
        void throwsConflict_whenDuplicateName() {
            // given
            when(brandRepository.existsByName("나이키")).thenReturn(true);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> brandService.register("나이키", "스포츠 브랜드"));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    () -> verify(brandRepository, never()).save(any(Brand.class)));
        }
    }

    @Nested
    @DisplayName("브랜드를 조회할 때,")
    class FindById {

        @Test
        @DisplayName("존재하는 브랜드 ID로 조회하면 브랜드를 반환한다.")
        void returnsBrand_whenExists() {
            // given
            Brand brand = createBrandWithId(1L, "나이키", "스포츠", BrandStatus.ACTIVE);
            when(brandRepository.findById(1L)).thenReturn(Optional.of(brand));

            // when
            Brand result = brandService.findById(1L);

            // then
            assertThat(result.getName()).isEqualTo("나이키");
        }

        @Test
        @DisplayName("존재하지 않는 브랜드 ID로 조회하면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenNotExists() {
            // given
            when(brandRepository.findById(999L)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> brandService.findById(999L));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("활성 브랜드를 조회할 때,")
    class FindActiveBrand {

        @Test
        @DisplayName("ACTIVE 상태 브랜드를 조회하면 브랜드를 반환한다.")
        void returnsBrand_whenActive() {
            // given
            Brand brand = createBrandWithId(1L, "나이키", "스포츠", BrandStatus.ACTIVE);
            when(brandRepository.findById(1L)).thenReturn(Optional.of(brand));

            // when
            Brand result = brandService.findActiveBrand(1L);

            // then
            assertThat(result.getName()).isEqualTo("나이키");
        }

        @Test
        @DisplayName("비활성 브랜드를 조회하면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenNotActive() {
            // given
            Brand brand = createBrandWithId(1L, "나이키", "스포츠", BrandStatus.PENDING);
            when(brandRepository.findById(1L)).thenReturn(Optional.of(brand));

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> brandService.findActiveBrand(1L));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("브랜드를 수정할 때,")
    class Update {

        @Test
        @DisplayName("유효한 정보로 수정하면 브랜드가 수정된다.")
        void updatesBrand_whenValidInfo() {
            // given
            Brand brand = createBrandWithId(1L, "나이키", "스포츠", BrandStatus.PENDING);
            when(brandRepository.findById(1L)).thenReturn(Optional.of(brand));
            when(brandRepository.existsByNameAndIdNot("아디다스", 1L)).thenReturn(false);
            when(brandRepository.save(any(Brand.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            Brand result = brandService.update(1L, "아디다스", "독일 스포츠 브랜드", BrandStatus.ACTIVE);

            // then
            assertAll(
                    () -> assertThat(result.getName()).isEqualTo("아디다스"),
                    () -> assertThat(result.getStatus()).isEqualTo(BrandStatus.ACTIVE));
        }

        @Test
        @DisplayName("다른 브랜드와 이름이 중복되면 CONFLICT 예외가 발생한다.")
        void throwsConflict_whenDuplicateNameExcludingSelf() {
            // given
            Brand brand = createBrandWithId(1L, "나이키", "스포츠", BrandStatus.PENDING);
            when(brandRepository.findById(1L)).thenReturn(Optional.of(brand));
            when(brandRepository.existsByNameAndIdNot("아디다스", 1L)).thenReturn(true);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> brandService.update(1L, "아디다스", "독일", BrandStatus.ACTIVE));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    () -> verify(brandRepository, never()).save(any(Brand.class)));
        }
    }

    @Nested
    @DisplayName("브랜드를 삭제할 때,")
    class SoftDelete {

        @Test
        @DisplayName("존재하는 브랜드를 삭제하면 softDelete가 호출된다.")
        void callsSoftDelete_whenExists() {
            // given
            Brand brand = createBrandWithId(1L, "나이키", "스포츠", BrandStatus.ACTIVE);
            when(brandRepository.findById(1L)).thenReturn(Optional.of(brand));

            // when
            brandService.softDelete(1L);

            // then
            verify(brandRepository, times(1)).softDelete(1L);
        }

        @Test
        @DisplayName("존재하지 않는 브랜드를 삭제하면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenNotExists() {
            // given
            when(brandRepository.findById(999L)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> brandService.softDelete(999L));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("브랜드 목록을 조회할 때,")
    class FindAll {

        @Test
        @DisplayName("빈 페이지도 정상 반환한다.")
        void returnsEmptyPage_whenNoData() {
            // given
            Pageable pageable = PageRequest.of(0, 20);
            when(brandRepository.findAll(pageable))
                    .thenReturn(new PageImpl<>(Collections.emptyList(), pageable, 0));

            // when
            Page<Brand> result = brandService.findAll(pageable);

            // then
            assertThat(result.getContent()).isEmpty();
        }
    }
}
