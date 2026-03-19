package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentJpaRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdAndDeletedAtIsNull(Long id);

    Optional<Payment> findByOrderIdAndDeletedAtIsNull(Long orderId);

    List<Payment> findAllByStatusAndDeletedAtIsNull(PaymentStatus status);
}
