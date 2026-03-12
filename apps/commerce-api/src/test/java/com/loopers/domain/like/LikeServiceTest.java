package com.loopers.domain.like;

import com.loopers.domain.product.Product;
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

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [단위 테스트 - Service with Mock]
 *
 * 테스트 대상: LikeService
 * 테스트 유형: 단위 테스트 (Mock 사용)
 * 테스트 더블: Mock (LikeRepository)
 *
 * LikeService는 Like 엔티티의 상태 전환만 담당한다.
 * 상품 존재 여부 확인, likeCount 증감은 Facade에서 처리하므로 이 테스트에서는 검증하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LikeService 단위 테스트")
class LikeServiceTest {

    @Mock
    private LikeRepository likeRepository;

    @InjectMocks
    private LikeService likeService;

    private static final Long MEMBER_ID = 1L;
    private static final Long PRODUCT_ID = 1L;

    private Product createProduct() {
        Product product = new Product(null, "테스트 상품", 10000, 8000, 0,
                0, "설명", null, null, "Y", List.of());
        ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
        return product;
    }

    private Like createLikeWithId(Long id, Long memberId, Long productId, String likeYn) {
        Like like = Like.create(memberId, productId);
        ReflectionTestUtils.setField(like, "id", id);
        ReflectionTestUtils.setField(like, "likeYn", likeYn);
        ReflectionTestUtils.setField(like, "createdAt", ZonedDateTime.now());
        ReflectionTestUtils.setField(like, "updatedAt", ZonedDateTime.now());
        return like;
    }

    @Nested
    @DisplayName("좋아요를 할 때,")
    class LikeAction {

        @Test
        @DisplayName("좋아요 기록이 없으면 새로 생성하고 true를 반환한다.")
        void createsNewLike_whenNoExistingRecord() {
            // given
            when(likeRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).thenReturn(Optional.empty());
            when(likeRepository.save(any(Like.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            boolean result = likeService.like(MEMBER_ID, PRODUCT_ID);

            // then
            assertAll(
                    () -> assertThat(result).isTrue(),
                    () -> verify(likeRepository, times(1)).save(any(Like.class)));
        }

        @Test
        @DisplayName("좋아요 취소 상태(N)에서 좋아요하면 Y로 전환하고 true를 반환한다.")
        void changesLikeYnToY_whenPreviouslyUnliked() {
            // given
            Like existingLike = createLikeWithId(1L, MEMBER_ID, PRODUCT_ID, "N");

            when(likeRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).thenReturn(Optional.of(existingLike));
            when(likeRepository.save(any(Like.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            boolean result = likeService.like(MEMBER_ID, PRODUCT_ID);

            // then
            assertAll(
                    () -> assertThat(result).isTrue(),
                    () -> verify(likeRepository, times(1)).save(any(Like.class)));
        }

        @Test
        @DisplayName("이미 좋아요 상태(Y)이면 아무것도 하지 않고 false를 반환한다. (멱등성)")
        void doesNothing_whenAlreadyLiked() {
            // given
            Like existingLike = createLikeWithId(1L, MEMBER_ID, PRODUCT_ID, "Y");

            when(likeRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).thenReturn(Optional.of(existingLike));

            // when
            boolean result = likeService.like(MEMBER_ID, PRODUCT_ID);

            // then
            assertAll(
                    () -> assertThat(result).isFalse(),
                    () -> verify(likeRepository, never()).save(any(Like.class)));
        }
    }

    @Nested
    @DisplayName("좋아요를 취소할 때,")
    class UnlikeAction {

        @Test
        @DisplayName("좋아요 상태(Y)에서 취소하면 N으로 전환하고 true를 반환한다.")
        void changesLikeYnToN_whenCurrentlyLiked() {
            // given
            Like existingLike = createLikeWithId(1L, MEMBER_ID, PRODUCT_ID, "Y");

            when(likeRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).thenReturn(Optional.of(existingLike));
            when(likeRepository.save(any(Like.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            boolean result = likeService.unlike(MEMBER_ID, PRODUCT_ID);

            // then
            assertAll(
                    () -> assertThat(result).isTrue(),
                    () -> verify(likeRepository, times(1)).save(any(Like.class)));
        }

        @Test
        @DisplayName("이미 좋아요 취소 상태(N)이면 아무것도 하지 않고 false를 반환한다. (멱등성)")
        void doesNothing_whenAlreadyUnliked() {
            // given
            Like existingLike = createLikeWithId(1L, MEMBER_ID, PRODUCT_ID, "N");

            when(likeRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).thenReturn(Optional.of(existingLike));

            // when
            boolean result = likeService.unlike(MEMBER_ID, PRODUCT_ID);

            // then
            assertAll(
                    () -> assertThat(result).isFalse(),
                    () -> verify(likeRepository, never()).save(any(Like.class)));
        }

        @Test
        @DisplayName("좋아요 기록이 없으면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenNoLikeRecord() {
            // given
            when(likeRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> likeService.unlike(MEMBER_ID, PRODUCT_ID));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("좋아요"),
                    () -> verify(likeRepository, never()).save(any(Like.class)));
        }
    }

    @Nested
    @DisplayName("좋아요 상품 목록을 조회할 때,")
    class GetLikedProducts {

        @Test
        @DisplayName("좋아요한 상품 목록이 페이지로 반환된다.")
        void returnsPagedProducts_whenLikedProductsExist() {
            // given
            Pageable pageable = PageRequest.of(0, 10);
            Product product = createProduct();
            Page<Product> expectedPage = new PageImpl<>(List.of(product), pageable, 1);

            when(likeRepository.findLikedProductsByMemberId(MEMBER_ID, pageable)).thenReturn(expectedPage);

            // when
            Page<Product> result = likeService.getLikedProducts(MEMBER_ID, pageable);

            // then
            assertAll(
                    () -> assertThat(result.getContent()).hasSize(1),
                    () -> assertThat(result.getTotalElements()).isEqualTo(1),
                    () -> verify(likeRepository, times(1)).findLikedProductsByMemberId(MEMBER_ID, pageable));
        }

        @Test
        @DisplayName("좋아요한 상품이 없으면 빈 페이지가 반환된다.")
        void returnsEmptyPage_whenNoLikedProducts() {
            // given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Product> emptyPage = Page.empty(pageable);

            when(likeRepository.findLikedProductsByMemberId(MEMBER_ID, pageable)).thenReturn(emptyPage);

            // when
            Page<Product> result = likeService.getLikedProducts(MEMBER_ID, pageable);

            // then
            assertAll(
                    () -> assertThat(result.getContent()).isEmpty(),
                    () -> assertThat(result.getTotalElements()).isEqualTo(0),
                    () -> verify(likeRepository, times(1)).findLikedProductsByMemberId(MEMBER_ID, pageable));
        }
    }
}
