package com.loopers.domain.like;

import com.loopers.domain.product.Product;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import java.util.Optional;

/**
 * 좋아요 도메인 서비스.
 * Like 엔티티의 상태 전환(생성, 활성화, 비활성화)만 담당한다.
 * 상품 존재 여부 확인, likeCount 증감 등 타 도메인 연동은 상위 레이어(Facade)에서 처리한다.
 * 반드시 상위 레이어(@Transactional)의 트랜잭션 내에서 호출되어야 한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class LikeService {

    private final LikeRepository likeRepository;

    /**
     * 좋아요를 등록한다.
     * 이미 좋아요 상태면 멱등하게 동작하며, 실제 likeCount 변경이 필요한지를 반환한다.
     *
     * @return 실제로 좋아요 상태가 변경(신규 생성 또는 N→Y 전환)되었으면 true, 이미 좋아요 상태면 false
     */
    public boolean like(Long memberId, Long productId) {
        Optional<Like> existingLike = likeRepository.findByMemberIdAndProductId(memberId, productId);

        if (existingLike.isPresent()) {
            Like like = existingLike.get();

            // 이미 좋아요 상태면 아무것도 하지 않음 (멱등성)
            if (like.isLiked()) {
                return false;
            }

            // N → Y 전환
            like.like();
            likeRepository.save(like);
            return true;
        }

        // 신규 좋아요 생성
        // 동시 요청에 의한 UniqueConstraint 위반은 DataIntegrityViolationException으로 발생하며,
        // ApiControllerAdvice에서 409 CONFLICT로 처리된다.
        Like newLike = Like.create(memberId, productId);
        likeRepository.save(newLike);
        return true;
    }

    /**
     * 좋아요를 취소한다.
     * 이미 취소 상태면 멱등하게 동작하며, 실제 likeCount 변경이 필요한지를 반환한다.
     *
     * @return 실제로 취소 상태로 변경(Y→N 전환)되었으면 true, 이미 취소 상태면 false
     */
    public boolean unlike(Long memberId, Long productId) {
        Like like = likeRepository.findByMemberIdAndProductId(memberId, productId)
                .orElseThrow(() -> new CoreException(ErrorType.BAD_REQUEST, "좋아요 기록이 존재하지 않습니다."));

        // 이미 좋아요 취소 상태면 아무것도 하지 않음 (멱등성)
        if (!like.isLiked()) {
            return false;
        }

        // Y → N 전환
        like.unlike();
        likeRepository.save(like);
        return true;
    }

    public Page<Product> getLikedProducts(Long memberId, Pageable pageable) {
        return likeRepository.findLikedProductsByMemberId(memberId, pageable);
    }

    /**
     * Like 테이블의 집계 결과를 product_like_summary 테이블에 갱신한다.
     * Materialized View 방식에서 주기적(배치/스케줄러)으로 호출된다.
     */
    public void refreshLikeSummary() {
        likeRepository.refreshLikeSummary();
    }
}
