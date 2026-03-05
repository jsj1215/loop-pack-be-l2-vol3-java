package com.loopers.domain.like;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Component
public class LikeService {

    private final LikeRepository likeRepository;
    private final ProductRepository productRepository;

    public Like like(Long memberId, Long productId) {
        // 1. 상품 존재 여부 확인
        productRepository.findById(productId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));

        // 2. 기존 좋아요 조회
        Optional<Like> existingLike = likeRepository.findByMemberIdAndProductId(memberId, productId);

        if (existingLike.isPresent()) {
            Like like = existingLike.get();

            // 이미 좋아요 상태면 아무것도 하지 않음 (멱등성)
            if (like.isLiked()) {
                return like;
            }

            // N → Y 전환
            like.like();
            Like savedLike = likeRepository.save(like);
            productRepository.incrementLikeCount(productId);
            return savedLike;
        }

        // 3. 신규 좋아요 생성
        // 동시 요청에 의한 UniqueConstraint 위반은 DataIntegrityViolationException으로 발생하며,
        // ApiControllerAdvice에서 409 CONFLICT로 처리된다.
        Like newLike = Like.create(memberId, productId);
        Like savedLike = likeRepository.save(newLike);
        productRepository.incrementLikeCount(productId);
        return savedLike;
    }

    public Like unlike(Long memberId, Long productId) {
        // 1. 기존 좋아요 조회
        Like like = likeRepository.findByMemberIdAndProductId(memberId, productId)
                .orElseThrow(() -> new CoreException(ErrorType.BAD_REQUEST, "좋아요 기록이 존재하지 않습니다."));

        // 이미 좋아요 취소 상태면 아무것도 하지 않음 (멱등성)
        if (!like.isLiked()) {
            return like;
        }

        // 2. Y → N 전환
        like.unlike();
        Like savedLike = likeRepository.save(like);

        // 3. 상품 좋아요 수 감소 (Atomic UPDATE)
        int updatedRows = productRepository.decrementLikeCount(productId);
        if (updatedRows == 0) {
            log.warn("좋아요 수 감소 실패: productId={}, likeCount가 이미 0입니다.", productId);
        }

        return savedLike;
    }

    public Page<Product> getLikedProducts(Long memberId, Pageable pageable) {
        return likeRepository.findLikedProductsByMemberId(memberId, pageable);
    }
}
