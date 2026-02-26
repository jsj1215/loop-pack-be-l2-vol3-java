package com.loopers.domain.like;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class LikeService {

    private final LikeRepository likeRepository;
    private final ProductRepository productRepository;

    public Like like(Long memberId, Long productId) {
        // 1. 상품 존재 여부 확인
        Product product = productRepository.findById(productId)
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
            product.incrementLikeCount();
            productRepository.save(product);
            return savedLike;
        }

        // 3. 신규 좋아요 생성
        Like newLike = Like.create(memberId, productId);
        Like savedLike = likeRepository.save(newLike);
        product.incrementLikeCount();
        productRepository.save(product);
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

        // 3. 상품 좋아요 수 감소
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        product.decrementLikeCount();
        productRepository.save(product);

        return savedLike;
    }

    public Page<Product> getLikedProducts(Long memberId, Pageable pageable) {
        return likeRepository.findLikedProductsByMemberId(memberId, pageable);
    }
}
