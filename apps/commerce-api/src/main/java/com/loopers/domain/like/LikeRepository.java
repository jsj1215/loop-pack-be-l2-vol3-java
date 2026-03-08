package com.loopers.domain.like;

import com.loopers.domain.product.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface LikeRepository {

    Optional<Like> findByMemberIdAndProductId(Long memberId, Long productId);

    Page<Product> findLikedProductsByMemberId(Long memberId, Pageable pageable);

    Like save(Like like);
}
