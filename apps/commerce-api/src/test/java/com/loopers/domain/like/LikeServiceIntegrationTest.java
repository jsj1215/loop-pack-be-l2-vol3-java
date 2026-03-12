package com.loopers.domain.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [통합 테스트 - Service Integration]
 *
 * 테스트 대상: LikeService (Domain Layer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Service → Repository → Database
 *
 * 주의: LikeService는 Like 엔티티의 상태 전환만 담당한다.
 * likeCount 증감은 Facade에서 처리하므로 이 테스트에서는 검증하지 않는다.
 */
@SpringBootTest
@Transactional
class LikeServiceIntegrationTest {

    @Autowired
    private LikeService likeService;

    @Autowired
    private ProductService productService;

    @Autowired
    private BrandService brandService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Product createProduct() {
        Brand brand = brandService.register("나이키", "스포츠 브랜드");
        brand = brandService.update(brand.getId(), "나이키", "스포츠 브랜드", BrandStatus.ACTIVE);
        ProductOption option = new ProductOption(null, "사이즈 270", 100);
        return productService.register(brand, "에어맥스", 100000, MarginType.RATE, 10,
                0, 3000, "에어맥스 설명", List.of(option));
    }

    @DisplayName("좋아요를 할 때,")
    @Nested
    class LikeAction {

        @Test
        @DisplayName("신규 좋아요를 추가하면, true를 반환한다.")
        void returnsTrue_whenNewLike() {
            // given
            Product product = createProduct();
            Long memberId = 1L;

            // when
            boolean changed = likeService.like(memberId, product.getId());

            // then
            assertThat(changed).isTrue();
        }

        @Test
        @DisplayName("이미 좋아요한 상태에서 다시 좋아요하면, false를 반환한다. (멱등성)")
        void returnsFalse_whenAlreadyLiked() {
            // given
            Product product = createProduct();
            Long memberId = 1L;
            likeService.like(memberId, product.getId());

            // when
            boolean changed = likeService.like(memberId, product.getId());

            // then
            assertThat(changed).isFalse();
        }
    }

    @DisplayName("좋아요를 취소할 때,")
    @Nested
    class UnlikeAction {

        @Test
        @DisplayName("좋아요를 취소하면, true를 반환한다.")
        void returnsTrue_whenUnlike() {
            // given
            Product product = createProduct();
            Long memberId = 1L;
            likeService.like(memberId, product.getId());

            // when
            boolean changed = likeService.unlike(memberId, product.getId());

            // then
            assertThat(changed).isTrue();
        }

        @Test
        @DisplayName("이미 취소된 상태에서 다시 취소하면, false를 반환한다. (멱등성)")
        void returnsFalse_whenAlreadyUnliked() {
            // given
            Product product = createProduct();
            Long memberId = 1L;
            likeService.like(memberId, product.getId());
            likeService.unlike(memberId, product.getId());

            // when
            boolean changed = likeService.unlike(memberId, product.getId());

            // then
            assertThat(changed).isFalse();
        }

        @Test
        @DisplayName("좋아요 기록이 없는 상태에서 취소하면, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNoLikeRecord() {
            // given
            Product product = createProduct();
            Long memberId = 1L;

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> likeService.unlike(memberId, product.getId()));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("좋아요 순차 전환 시,")
    @Nested
    class LikeStateTransition {

        @Test
        @DisplayName("좋아요 → 취소 → 다시 좋아요하면, 모두 true를 반환한다.")
        void allTransitionsReturnTrue() {
            // given
            Product product = createProduct();
            Long memberId = 1L;

            // when & then
            assertThat(likeService.like(memberId, product.getId())).isTrue();
            assertThat(likeService.unlike(memberId, product.getId())).isTrue();
            assertThat(likeService.like(memberId, product.getId())).isTrue();
        }

        @Test
        @DisplayName("여러 회원이 좋아요하면, 모두 true를 반환한다.")
        void allLikesReturnTrue_whenMultipleMembers() {
            // given
            Product product = createProduct();
            int memberCount = 5;

            // when & then
            for (long memberId = 1; memberId <= memberCount; memberId++) {
                assertThat(likeService.like(memberId, product.getId())).isTrue();
            }
        }
    }
}
