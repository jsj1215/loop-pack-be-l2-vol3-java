package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.ZonedDateTime;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<Order, Long> {

    @EntityGraph(attributePaths = "orderItems")
    Optional<Order> findByIdAndDeletedAtIsNull(Long id);

    @EntityGraph(attributePaths = "orderItems")
    Page<Order> findByMemberIdAndCreatedAtBetweenAndDeletedAtIsNull(
            Long memberId, ZonedDateTime startAt, ZonedDateTime endAt, Pageable pageable);
}
