package com.loopers.application.event;

import com.loopers.domain.event.ProductLikedEvent;
import com.loopers.domain.product.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * LikeEventListener 단위 테스트.
 *
 * 검증 대상:
 * - 좋아요 이벤트 수신 시 likeCount 증감 위임
 * - 집계 실패 시 예외 삼킴 (비즈니스 무영향, Eventual Consistency)
 */
@DisplayName("LikeEventListener 단위 테스트")
@ExtendWith(MockitoExtension.class)
class LikeEventListenerTest {

    @Mock
    private ProductService productService;

    private LikeEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new LikeEventListener(productService);
    }

    @Nested
    @DisplayName("좋아요 집계")
    class HandleProductLiked {

        @Test
        @DisplayName("좋아요 이벤트를 수신하면 likeCount를 증가시킨다")
        void incrementsLikeCount_whenLiked() {
            // given
            ProductLikedEvent event = new ProductLikedEvent(1L, 100L, true);

            // when
            listener.handleProductLiked(event);

            // then
            verify(productService).incrementLikeCount(1L);
            verify(productService, never()).decrementLikeCount(1L);
        }

        @Test
        @DisplayName("좋아요 취소 이벤트를 수신하면 likeCount를 감소시킨다")
        void decrementsLikeCount_whenUnliked() {
            // given
            ProductLikedEvent event = new ProductLikedEvent(1L, 100L, false);

            // when
            listener.handleProductLiked(event);

            // then
            verify(productService).decrementLikeCount(1L);
            verify(productService, never()).incrementLikeCount(1L);
        }

        @Test
        @DisplayName("집계 실패 시 예외를 삼키고 비즈니스에 영향을 주지 않는다")
        void suppressesException_whenAggregationFails() {
            // given
            ProductLikedEvent event = new ProductLikedEvent(1L, 100L, true);
            doThrow(new RuntimeException("DB error")).when(productService).incrementLikeCount(1L);

            // when — 예외가 전파되지 않아야 한다
            listener.handleProductLiked(event);

            // then
            verify(productService).incrementLikeCount(1L);
        }
    }
}
