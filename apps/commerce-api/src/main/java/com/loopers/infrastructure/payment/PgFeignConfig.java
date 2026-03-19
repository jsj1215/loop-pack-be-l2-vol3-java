package com.loopers.infrastructure.payment;

import feign.Request;
import feign.Retryer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * PG Feign 클라이언트 전용 설정 — 개별 HTTP 호출 레벨의 타임아웃과 리트라이 정책
 *
 * @FeignClient(configuration = PgFeignConfig.class)로 지정되어,
 * 이 설정은 PgFeignClient 인터페이스에만 적용된다 (글로벌 Feign 설정과 분리).
 *
 * @Configuration 어노테이션이 없는 이유:
 *   @FeignClient의 configuration에 지정된 클래스는 @Configuration을 붙이지 않아야 한다.
 *   붙이면 Spring Context에 글로벌로 등록되어 다른 Feign 클라이언트에도 영향을 줄 수 있다.
 */
public class PgFeignConfig {

    /**
     * Feign HTTP 타임아웃 설정 — 개별 HTTP 호출 1건의 시간을 제한
     *
     * connect timeout (기본 1초): TCP 3-way handshake 완료까지 대기 시간.
     *   PG 서버와 TCP 연결을 맺는 데 1초 이상 걸리면 연결 실패로 처리한다.
     *
     * read timeout (기본 3초): 요청 전송 후 응답 수신까지 대기 시간.
     *   PG가 요청을 받고 응답을 보내는 데 3초 이상 걸리면 읽기 타임아웃으로 처리한다.
     *
     * 세 번째 파라미터(followRedirects=true): HTTP 리다이렉트(3xx)를 자동으로 따른다.
     *
     * 주의: 이 타임아웃은 개별 HTTP 호출 1건에 대한 것이다.
     * Resilience4j의 TimeLimiter(4초)도 개별 호출 단위로 동작한다 (Retry 안쪽에 위치).
     * TimeLimiter는 Feign read timeout(3초)의 안전망 역할이며, Feign이 정상 타임아웃하면 동작하지 않는다.
     */
    @Bean
    public Request.Options feignOptions(
            @Value("${pg.timeout.connect:1000}") int connectTimeout,
            @Value("${pg.timeout.read:3000}") int readTimeout) {
        return new Request.Options(
                connectTimeout, TimeUnit.MILLISECONDS,
                readTimeout, TimeUnit.MILLISECONDS,
                true);
    }

    /**
     * Feign 자체 리트라이를 비활성화한다 (Retryer.NEVER_RETRY).
     *
     * 비활성화하는 이유:
     *   리트라이는 Resilience4j(@Retry)가 담당한다.
     *   Feign 리트라이와 Resilience4j 리트라이가 동시에 활성화되면
     *   리트라이 횟수가 곱셈(Feign 3회 × Resilience4j 3회 = 9회)으로 폭발할 수 있다.
     *   한 곳에서만 리트라이를 관리해야 예측 가능한 동작을 보장할 수 있다.
     */
    @Bean
    public Retryer feignRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
