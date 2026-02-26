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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [통합 테스트 - Service Integration]
 *
 * 테스트 대상: LikeService (Domain Layer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Service → Repository → Database
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
        @DisplayName("좋아요를 추가하면, likeCount가 증가한다.")
        void incrementsLikeCount_whenLike() {
            // given
            Product product = createProduct();
            Long memberId = 1L;

            // when
            Like like = likeService.like(memberId, product.getId());

            // then
            assertAll(
                    () -> assertThat(like.getId()).isNotNull(),
                    () -> assertThat(like.getMemberId()).isEqualTo(memberId),
                    () -> assertThat(like.getProductId()).isEqualTo(product.getId()),
                    () -> assertThat(like.isLiked()).isTrue()
            );

            Product updatedProduct = productService.findById(product.getId());
            assertThat(updatedProduct.getLikeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("이미 좋아요한 상태에서 다시 좋아요하면, 멱등하게 동작한다.")
        void idempotent_whenAlreadyLiked() {
            // given
            Product product = createProduct();
            Long memberId = 1L;
            likeService.like(memberId, product.getId());

            // when
            Like like = likeService.like(memberId, product.getId());

            // then
            assertThat(like.isLiked()).isTrue();

            Product updatedProduct = productService.findById(product.getId());
            assertThat(updatedProduct.getLikeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("존재하지 않는 상품에 좋아요하면, NOT_FOUND 예외가 발생한다.")
        void throwsException_whenProductNotFound() {
            // given
            Long memberId = 1L;
            Long nonExistentProductId = 999L;

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> likeService.like(memberId, nonExistentProductId));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("좋아요를 취소할 때,")
    @Nested
    class UnlikeAction {

        @Test
        @DisplayName("좋아요를 취소하면, likeCount가 감소한다.")
        void decrementsLikeCount_whenUnlike() {
            // given
            Product product = createProduct();
            Long memberId = 1L;
            likeService.like(memberId, product.getId());

            // when
            Like like = likeService.unlike(memberId, product.getId());

            // then
            assertThat(like.isLiked()).isFalse();

            Product updatedProduct = productService.findById(product.getId());
            assertThat(updatedProduct.getLikeCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("이미 취소된 상태에서 다시 취소하면, 멱등하게 동작한다.")
        void idempotent_whenAlreadyUnliked() {
            // given
            Product product = createProduct();
            Long memberId = 1L;
            likeService.like(memberId, product.getId());
            likeService.unlike(memberId, product.getId());

            // when
            Like like = likeService.unlike(memberId, product.getId());

            // then
            assertThat(like.isLiked()).isFalse();

            Product updatedProduct = productService.findById(product.getId());
            assertThat(updatedProduct.getLikeCount()).isEqualTo(0);
        }
    }
}
