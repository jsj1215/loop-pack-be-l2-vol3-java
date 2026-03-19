package com.loopers.application.payment;

import com.loopers.application.order.OrderCompensationService;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 결제 Application Facade — Resilience4j 기반 PG 연동 (V2 컨트롤러에서 사용)
 *
 * DIP(의존성 역전 원칙) 적용:
 *   PaymentFacade → PaymentGateway (Application → Domain 인터페이스 의존)
 *   Resilience4jPaymentGateway implements PaymentGateway (Infrastructure → Domain 의존)
 */
@Component
public class PaymentFacade extends AbstractPaymentFacade {

    public PaymentFacade(
            PaymentService paymentService,
            @Qualifier("resilience4jPaymentGateway") PaymentGateway paymentGateway,
            OrderService orderService,
            OrderCompensationService orderCompensationService) {
        super(paymentService, paymentGateway, orderService, orderCompensationService);
    }
}
