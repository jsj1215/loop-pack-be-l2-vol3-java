package com.loopers.domain.like;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("Like 도메인 모델")
class LikeTest {

    @Nested
    @DisplayName("생성할 때,")
    class Create {

        @Test
        @DisplayName("create로 생성하면 likeYn이 Y로 설정된다.")
        void createsWithLikeYnY_whenCreate() {
            // given
            Long memberId = 1L;
            Long productId = 1L;

            // when
            Like like = Like.create(memberId, productId);

            // then
            assertAll(
                    () -> assertThat(like.getId()).isNull(),
                    () -> assertThat(like.getMemberId()).isEqualTo(1L),
                    () -> assertThat(like.getProductId()).isEqualTo(1L),
                    () -> assertThat(like.getLikeYn()).isEqualTo("Y"),
                    () -> assertThat(like.isLiked()).isTrue());
        }
    }

    @Nested
    @DisplayName("좋아요 상태를 변경할 때,")
    class ChangeState {

        @Test
        @DisplayName("like를 호출하면 likeYn이 Y로 변경된다.")
        void changesLikeYnToY_whenLike() {
            // given
            Like like = Like.create(1L, 1L);
            like.unlike(); // N 상태로 전환

            // when
            like.like();

            // then
            assertAll(
                    () -> assertThat(like.getLikeYn()).isEqualTo("Y"),
                    () -> assertThat(like.isLiked()).isTrue());
        }

        @Test
        @DisplayName("unlike를 호출하면 likeYn이 N으로 변경된다.")
        void changesLikeYnToN_whenUnlike() {
            // given
            Like like = Like.create(1L, 1L);

            // when
            like.unlike();

            // then
            assertAll(
                    () -> assertThat(like.getLikeYn()).isEqualTo("N"),
                    () -> assertThat(like.isLiked()).isFalse());
        }

        @Test
        @DisplayName("이미 Y인 상태에서 like를 호출해도 Y를 유지한다.")
        void remainsY_whenAlreadyLiked() {
            // given
            Like like = Like.create(1L, 1L);

            // when
            like.like();

            // then
            assertAll(
                    () -> assertThat(like.getLikeYn()).isEqualTo("Y"),
                    () -> assertThat(like.isLiked()).isTrue());
        }

        @Test
        @DisplayName("이미 N인 상태에서 unlike를 호출해도 N을 유지한다.")
        void remainsN_whenAlreadyUnliked() {
            // given
            Like like = Like.create(1L, 1L);
            like.unlike(); // N 상태로 전환

            // when
            like.unlike();

            // then
            assertAll(
                    () -> assertThat(like.getLikeYn()).isEqualTo("N"),
                    () -> assertThat(like.isLiked()).isFalse());
        }
    }

    @Nested
    @DisplayName("isLiked를 확인할 때,")
    class IsLiked {

        @Test
        @DisplayName("likeYn이 Y면 true를 반환한다.")
        void returnsTrue_whenLikeYnIsY() {
            // given
            Like like = Like.create(1L, 1L);

            // when
            boolean result = like.isLiked();

            // then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("likeYn이 N이면 false를 반환한다.")
        void returnsFalse_whenLikeYnIsN() {
            // given
            Like like = Like.create(1L, 1L);
            like.unlike(); // N 상태로 전환

            // when
            boolean result = like.isLiked();

            // then
            assertThat(result).isFalse();
        }
    }
}
