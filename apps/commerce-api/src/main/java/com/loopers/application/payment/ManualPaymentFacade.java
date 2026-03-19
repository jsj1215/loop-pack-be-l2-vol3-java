package com.loopers.application.payment;

import com.loopers.application.order.OrderCompensationService;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 결제 Application Facade — 수동 서킷브레이커 기반 PG 연동 (V1 컨트롤러에서 사용)
 *
 * DIP 적용: PaymentGateway 인터페이스(Domain)에 의존하며,
 * ManualPaymentGateway(Infrastructure)가 수동 서킷브레이커/리트라이를 내부 처리한다.
 */
@Component
public class ManualPaymentFacade extends AbstractPaymentFacade {

    public ManualPaymentFacade(
            PaymentService paymentService,
            @Qualifier("manualPaymentGateway") PaymentGateway paymentGateway,
            OrderService orderService,
            OrderCompensationService orderCompensationService) {
        super(paymentService, paymentGateway, orderService, orderCompensationService);
    }
}
