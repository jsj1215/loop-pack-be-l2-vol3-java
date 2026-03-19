package com.loopers.infrastructure.payment;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * PG(Payment Gateway) Feign 클라이언트 — 외부 PG 서버와의 HTTP 통신 인터페이스
 *
 * OpenFeign이란: Spring Cloud에서 제공하는 선언적(Declarative) HTTP 클라이언트이다.
 * 인터페이스와 어노테이션만으로 HTTP API 호출 코드를 자동 생성한다.
 * RestTemplate이나 WebClient처럼 직접 HTTP 요청을 작성할 필요 없이,
 * Spring MVC 스타일의 어노테이션(@PostMapping, @RequestBody 등)으로 API를 정의한다.
 *
 * @FeignClient 속성:
 *   - name: Feign 클라이언트 빈 이름 (로드밸런서 사용 시 서비스 이름으로도 활용)
 *   - url: PG 서버의 기본 URL (application.yml의 pg.base-url에서 주입)
 *   - configuration: 이 클라이언트에만 적용되는 커스텀 설정 (타임아웃, 리트라이 정책 등)
 */
@FeignClient(name = "pgClient", url = "${pg.base-url}", configuration = PgFeignConfig.class)
public interface PgFeignClient {

    /**
     * PG 결제 요청 API — POST /api/v1/payments
     * PG에 결제를 접수하고, PG가 발급한 transactionId와 처리 상태를 응답으로 받는다.
     *
     * @RequestHeader("X-USER-ID"): PG가 요구하는 사용자 식별 헤더
     * @RequestBody: JSON으로 직렬화되어 PG에 전송되는 결제 정보
     */
    @PostMapping(value = "/api/v1/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    PgPaymentResponse requestPayment(
            @RequestHeader("X-USER-ID") String memberId,
            @RequestBody PgPaymentRequest request);

    /**
     * PG 결제 상태 조회 API — GET /api/v1/payments?orderId=...
     * 콜백 유실 시 PG에 직접 결제 상태를 확인하는 Polling 조회이다.
     * verify API에서 호출하여 우리 DB와 PG 상태를 동기화한다.
     */
    @GetMapping("/api/v1/payments")
    PgPaymentStatusResponse getPaymentStatus(
            @RequestHeader("X-USER-ID") String memberId,
            @RequestParam("orderId") String orderId);
}
