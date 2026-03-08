package com.loopers.infrastructure.cart;

import com.loopers.domain.cart.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CartJpaRepository extends JpaRepository<CartItem, Long> {

    Optional<CartItem> findByMemberIdAndProductOptionId(Long memberId, Long productOptionId);

    @Modifying
    @Query(value = "INSERT INTO cart_item (member_id, product_option_id, quantity, created_at, updated_at) " +
            "VALUES (:memberId, :productOptionId, :quantity, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE quantity = quantity + :quantity, updated_at = NOW()",
            nativeQuery = true)
    void upsert(@Param("memberId") Long memberId,
                @Param("productOptionId") Long productOptionId,
                @Param("quantity") int quantity);

    @Modifying
    @Query("DELETE FROM CartItem c WHERE c.productOptionId IN :productOptionIds")
    void deleteByProductOptionIdIn(@Param("productOptionIds") List<Long> productOptionIds);

    @Modifying
    @Query("DELETE FROM CartItem c WHERE c.memberId = :memberId AND c.productOptionId IN :productOptionIds")
    void deleteByMemberIdAndProductOptionIdIn(@Param("memberId") Long memberId,
                                              @Param("productOptionIds") List<Long> productOptionIds);
}
