package com.loopers.infrastructure.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.like.QLike;
import com.loopers.domain.brand.QBrand;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.QProduct;
import com.loopers.infrastructure.product.ProductOptionJpaRepository;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.stream.Collectors.groupingBy;

@RequiredArgsConstructor
@Component
public class LikeRepositoryImpl implements LikeRepository {

    private final LikeJpaRepository likeJpaRepository;
    private final ProductOptionJpaRepository productOptionJpaRepository;
    private final JPAQueryFactory queryFactory;

    private static final QLike like = QLike.like;
    private static final QProduct product = QProduct.product;
    private static final QBrand brand = QBrand.brand;

    @Override
    public Optional<Like> findByMemberIdAndProductId(Long memberId, Long productId) {
        return likeJpaRepository.findByMemberIdAndProductId(memberId, productId);
    }

    @Override
    public Page<Product> findLikedProductsByMemberId(Long memberId, Pageable pageable) {
        List<Product> entities = queryFactory
                .selectFrom(product)
                .join(product.brand, brand).fetchJoin()
                .join(like)
                .on(like.productId.eq(product.id)
                        .and(like.memberId.eq(memberId))
                        .and(like.likeYn.eq("Y")))
                .where(product.deletedAt.isNull())
                .orderBy(like.updatedAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(product.count())
                .from(product)
                .join(like)
                .on(like.productId.eq(product.id)
                        .and(like.memberId.eq(memberId))
                        .and(like.likeYn.eq("Y")))
                .where(product.deletedAt.isNull())
                .fetchOne();

        List<Long> productIds = entities.stream().map(Product::getId).toList();
        Map<Long, List<ProductOption>> optionsByProductId =
                productOptionJpaRepository.findAllByProductIdInAndDeletedAtIsNull(productIds)
                        .stream()
                        .collect(groupingBy(ProductOption::getProductId));

        List<Product> products = entities.stream()
                .map(p -> p.withOptions(optionsByProductId.getOrDefault(p.getId(), List.of())))
                .toList();

        return new PageImpl<>(products, pageable, total != null ? total : 0L);
    }

    @Override
    public Like save(Like like) {
        return likeJpaRepository.save(like);
    }
}
