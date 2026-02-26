package com.loopers.infrastructure.cart;

import com.loopers.domain.cart.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CartJpaRepository extends JpaRepository<CartItem, Long> {

    Optional<CartItem> findByMemberIdAndProductOptionIdAndDeletedAtIsNull(Long memberId, Long productOptionId);

    @Modifying
    @Query("UPDATE CartItem c SET c.deletedAt = CURRENT_TIMESTAMP WHERE c.productOptionId IN :productOptionIds AND c.deletedAt IS NULL")
    void deleteByProductOptionIdIn(@Param("productOptionIds") List<Long> productOptionIds);

    @Modifying
    @Query("UPDATE CartItem c SET c.deletedAt = CURRENT_TIMESTAMP WHERE c.memberId = :memberId AND c.productOptionId IN :productOptionIds AND c.deletedAt IS NULL")
    void deleteByMemberIdAndProductOptionIdIn(@Param("memberId") Long memberId, @Param("productOptionIds") List<Long> productOptionIds);
}
