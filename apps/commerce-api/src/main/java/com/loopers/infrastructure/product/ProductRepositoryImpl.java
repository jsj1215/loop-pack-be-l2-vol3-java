package com.loopers.infrastructure.product;

import com.loopers.domain.product.AdminProductSearchCondition;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSearchCondition;
import com.loopers.domain.product.ProductStatus;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.loopers.domain.brand.QBrand.brand;
import static com.loopers.domain.product.QProduct.product;
import static java.util.stream.Collectors.groupingBy;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;
    private final ProductOptionJpaRepository productOptionJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public Product save(Product productToSave) {
        Product savedProduct = productJpaRepository.save(productToSave);

        // 기존 옵션 삭제 후 새로 저장
        if (productToSave.getOptions() != null && !productToSave.getOptions().isEmpty()) {
            productOptionJpaRepository.deleteAllByProductId(savedProduct.getId());

            List<ProductOption> optionsToSave = productToSave.getOptions().stream()
                    .map(option -> new ProductOption(savedProduct.getId(), option.getOptionName(), option.getStockQuantity()))
                    .toList();
            List<ProductOption> savedOptions = productOptionJpaRepository.saveAll(optionsToSave);

            return savedProduct.withOptions(savedOptions);
        }

        List<ProductOption> existingOptions = productOptionJpaRepository
                .findAllByProductIdAndDeletedAtIsNull(savedProduct.getId());

        return savedProduct.withOptions(existingOptions);
    }

    @Override
    public Optional<Product> findById(Long id) {
        return productJpaRepository.findByIdAndDeletedAtIsNull(id)
                .map(entity -> {
                    List<ProductOption> options = productOptionJpaRepository
                            .findAllByProductIdAndDeletedAtIsNull(entity.getId());
                    return entity.withOptions(options);
                });
    }

    @Override
    public Optional<ProductOption> findOptionById(Long optionId) {
        return productOptionJpaRepository.findByIdAndDeletedAtIsNull(optionId);
    }

    @Override
    public boolean existsByBrandIdAndName(Long brandId, String name) {
        return productJpaRepository.existsByBrand_IdAndNameAndDeletedAtIsNull(brandId, name);
    }

    @Override
    public boolean existsByBrandIdAndNameAndIdNot(Long brandId, String name, Long id) {
        return productJpaRepository.existsByBrand_IdAndNameAndIdNotAndDeletedAtIsNull(brandId, name, id);
    }

    @Override
    public Page<Product> search(ProductSearchCondition condition, Pageable pageable) {
        BooleanBuilder builder = new BooleanBuilder();
        builder.and(product.deletedAt.isNull());
        builder.and(product.status.eq(ProductStatus.ON_SALE));
        builder.and(product.displayYn.eq("Y"));

        if (condition.keyword() != null && !condition.keyword().isBlank()) {
            builder.and(product.name.containsIgnoreCase(condition.keyword()));
        }
        if (condition.brandId() != null) {
            builder.and(product.brand.id.eq(condition.brandId()));
        }

        OrderSpecifier<?> orderSpecifier = switch (condition.sort()) {
            case PRICE_ASC -> product.price.asc();
            case LIKE_DESC -> product.likeCount.desc();
            default -> product.createdAt.desc();
        };

        JPAQuery<Product> query = queryFactory
                .selectFrom(product)
                .join(product.brand, brand).fetchJoin()
                .where(builder)
                .orderBy(orderSpecifier)
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize());

        List<Product> products = query.fetch();

        Long total = queryFactory
                .select(product.count())
                .from(product)
                .where(builder)
                .fetchOne();

        List<Product> productsWithOptions = assembleWithOptions(products);

        return new PageImpl<>(productsWithOptions, pageable, total != null ? total : 0L);
    }

    @Override
    public Page<Product> adminSearch(AdminProductSearchCondition condition, Pageable pageable) {
        BooleanBuilder builder = new BooleanBuilder();
        builder.and(product.deletedAt.isNull());

        if (condition.searchType() != null && condition.searchValue() != null && !condition.searchValue().isBlank()) {
            switch (condition.searchType()) {
                case PRODUCT_ID -> builder.and(product.id.eq(Long.parseLong(condition.searchValue())));
                case PRODUCT_NAME -> builder.and(product.name.containsIgnoreCase(condition.searchValue()));
                case BRAND_ID -> builder.and(product.brand.id.eq(Long.parseLong(condition.searchValue())));
                case STATUS -> builder.and(product.status.stringValue().eq(condition.searchValue()));
                case DISPLAY_YN -> builder.and(product.displayYn.eq(condition.searchValue()));
            }
        }

        JPAQuery<Product> query = queryFactory
                .selectFrom(product)
                .join(product.brand, brand).fetchJoin()
                .where(builder)
                .orderBy(product.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize());

        List<Product> products = query.fetch();

        Long total = queryFactory
                .select(product.count())
                .from(product)
                .where(builder)
                .fetchOne();

        List<Product> productsWithOptions = assembleWithOptions(products);

        return new PageImpl<>(productsWithOptions, pageable, total != null ? total : 0L);
    }

    @Override
    public void softDelete(Long productId) {
        productJpaRepository.findById(productId)
                .ifPresent(entity -> {
                    entity.delete();
                    // 해당 상품의 옵션도 소프트 삭제
                    productOptionJpaRepository.findAllByProductIdAndDeletedAtIsNull(productId)
                            .forEach(ProductOption::delete);
                });
    }

    @Override
    public void softDeleteByBrandId(Long brandId) {
        List<Product> products = queryFactory
                .selectFrom(product)
                .where(product.brand.id.eq(brandId)
                        .and(product.deletedAt.isNull()))
                .fetch();

        List<Long> productIds = products.stream().map(Product::getId).toList();
        products.forEach(Product::delete);

        if (!productIds.isEmpty()) {
            productOptionJpaRepository.findAllByProductIdInAndDeletedAtIsNull(productIds)
                    .forEach(ProductOption::delete);
        }
    }

    @Override
    public List<Long> findOptionIdsByProductId(Long productId) {
        return productOptionJpaRepository.findAllByProductIdAndDeletedAtIsNull(productId)
                .stream()
                .map(ProductOption::getId)
                .toList();
    }

    @Override
    public List<Long> findOptionIdsByBrandId(Long brandId) {
        List<Long> productIds = queryFactory
                .select(product.id)
                .from(product)
                .where(product.brand.id.eq(brandId)
                        .and(product.deletedAt.isNull()))
                .fetch();

        if (productIds.isEmpty()) {
            return List.of();
        }

        return productOptionJpaRepository.findAllByProductIdInAndDeletedAtIsNull(productIds)
                .stream()
                .map(ProductOption::getId)
                .toList();
    }

    @Override
    public void saveOption(ProductOption option) {
        productOptionJpaRepository.save(option);
    }

    private List<Product> assembleWithOptions(List<Product> products) {
        if (products.isEmpty()) {
            return products;
        }

        List<Long> productIds = products.stream().map(Product::getId).toList();
        Map<Long, List<ProductOption>> optionsByProductId =
                productOptionJpaRepository.findAllByProductIdInAndDeletedAtIsNull(productIds)
                        .stream()
                        .collect(groupingBy(ProductOption::getProductId));

        return products.stream()
                .map(p -> p.withOptions(optionsByProductId.getOrDefault(p.getId(), List.of())))
                .toList();
    }
}
