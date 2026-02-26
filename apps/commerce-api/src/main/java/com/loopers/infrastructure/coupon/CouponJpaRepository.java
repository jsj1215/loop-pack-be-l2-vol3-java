package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.Coupon;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface CouponJpaRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByIdAndDeletedAtIsNull(Long id);

    Page<Coupon> findAllByDeletedAtIsNull(Pageable pageable);

    @Query("SELECT c FROM Coupon c WHERE c.deletedAt IS NULL " +
            "AND c.validFrom <= :now AND c.validTo >= :now " +
            "AND c.issuedQuantity < c.totalQuantity")
    List<Coupon> findAllValid(ZonedDateTime now);
}
