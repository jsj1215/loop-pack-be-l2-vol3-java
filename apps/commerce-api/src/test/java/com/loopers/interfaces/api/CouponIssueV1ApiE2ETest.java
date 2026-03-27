package com.loopers.interfaces.api;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.DiscountType;
import com.loopers.domain.coupon.MemberCouponStatus;
import com.loopers.domain.member.BirthDate;
import com.loopers.domain.member.Email;
import com.loopers.domain.member.LoginId;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberName;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.infrastructure.coupon.MemberCouponJpaRepository;
import com.loopers.infrastructure.member.MemberJpaRepository;
import com.loopers.interfaces.api.coupon.dto.CouponIssueV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [E2E 테스트]
 *
 * 대상: 선착순 쿠폰 발급 API 전체 흐름
 *
 * 테스트 범위: HTTP 요청 → Controller → Facade → Service → Repository → Database
 * Kafka 발행은 MockBean으로 대체 — 테스트 환경의 Kafka 연결 타임아웃 이슈 방지.
 * Kafka 연동은 streamer 쪽 통합 테스트에서 실제 메시지 소비로 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("선착순 쿠폰 발급 API E2E 테스트")
class CouponIssueV1ApiE2ETest {

    private static final String ENDPOINT_ISSUE_REQUEST = "/api/v1/coupons/{couponId}/issue-request";
    private static final String ENDPOINT_ISSUE_STATUS = "/api/v1/coupons/{couponId}/issue-status";
    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

    @SuppressWarnings("unchecked")
    @MockBean
    private KafkaTemplate<Object, Object> kafkaTemplate;

    private final TestRestTemplate testRestTemplate;
    private final MemberJpaRepository memberJpaRepository;
    private final CouponJpaRepository couponJpaRepository;
    private final MemberCouponJpaRepository memberCouponJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public CouponIssueV1ApiE2ETest(
            TestRestTemplate testRestTemplate,
            MemberJpaRepository memberJpaRepository,
            CouponJpaRepository couponJpaRepository,
            MemberCouponJpaRepository memberCouponJpaRepository,
            DatabaseCleanUp databaseCleanUp,
            PasswordEncoder passwordEncoder) {
        this.testRestTemplate = testRestTemplate;
        this.memberJpaRepository = memberJpaRepository;
        this.couponJpaRepository = couponJpaRepository;
        this.memberCouponJpaRepository = memberCouponJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
        this.passwordEncoder = passwordEncoder;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @BeforeEach
    void setUpKafkaMock() {
        SendResult<Object, Object> sendResult = new SendResult<>(
                new ProducerRecord<>("coupon-issue-requests", "1", "{}"),
                new RecordMetadata(new TopicPartition("coupon-issue-requests", 0), 0, 0, 0, 0, 0)
        );
        when(kafkaTemplate.send(anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
    }

    private Member saveMember() {
        Member member = new Member(
                new LoginId("testuser1"),
                passwordEncoder.encode("Password1!"),
                new MemberName("홍길동"),
                new Email("test@example.com"),
                new BirthDate("19900101"));
        return memberJpaRepository.save(member);
    }

    private Coupon saveLimitedCoupon(int maxIssueCount) {
        Coupon coupon = new Coupon(
                "선착순 쿠폰",
                CouponScope.CART,
                null,
                DiscountType.FIXED_AMOUNT,
                5000,
                10000,
                0,
                ZonedDateTime.now().minusDays(1),
                ZonedDateTime.now().plusDays(30),
                maxIssueCount);
        return couponJpaRepository.save(coupon);
    }

    private HttpHeaders memberAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_LOGIN_ID, "testuser1");
        headers.set(HEADER_LOGIN_PW, "Password1!");
        return headers;
    }

    @Nested
    @DisplayName("POST /api/v1/coupons/{couponId}/issue-request")
    class IssueRequest {

        @Test
        @DisplayName("인증된 사용자가 선착순 쿠폰 발급을 요청하면, 202 Accepted와 REQUESTED 상태를 반환한다")
        void returns202_whenAuthenticated() {
            // given
            saveMember();
            Coupon coupon = saveLimitedCoupon(100);

            // when
            ParameterizedTypeReference<ApiResponse<CouponIssueV1Dto.IssueStatusResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponIssueV1Dto.IssueStatusResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ISSUE_REQUEST.replace("{couponId}", coupon.getId().toString()),
                    HttpMethod.POST,
                    new HttpEntity<>(memberAuthHeaders()),
                    responseType);

            // then
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().status()).isEqualTo(MemberCouponStatus.REQUESTED)
            );
        }

        @Test
        @DisplayName("인증 없이 요청하면 401 Unauthorized를 반환한다")
        void returns401_whenNoAuth() {
            // given
            Coupon coupon = saveLimitedCoupon(100);

            // when
            ResponseEntity<String> response = testRestTemplate.exchange(
                    ENDPOINT_ISSUE_REQUEST.replace("{couponId}", coupon.getId().toString()),
                    HttpMethod.POST,
                    HttpEntity.EMPTY,
                    String.class);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("동일 쿠폰에 중복 요청하면 409 Conflict를 반환한다")
        void returns409_whenDuplicate() {
            // given
            saveMember();
            Coupon coupon = saveLimitedCoupon(100);

            ParameterizedTypeReference<ApiResponse<CouponIssueV1Dto.IssueStatusResponse>> responseType =
                    new ParameterizedTypeReference<>() {};

            // 첫 번째 요청
            testRestTemplate.exchange(
                    ENDPOINT_ISSUE_REQUEST.replace("{couponId}", coupon.getId().toString()),
                    HttpMethod.POST,
                    new HttpEntity<>(memberAuthHeaders()),
                    responseType);

            // when — 두 번째 요청
            ResponseEntity<String> response = testRestTemplate.exchange(
                    ENDPOINT_ISSUE_REQUEST.replace("{couponId}", coupon.getId().toString()),
                    HttpMethod.POST,
                    new HttpEntity<>(memberAuthHeaders()),
                    String.class);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/coupons/{couponId}/issue-status")
    class IssueStatus {

        @Test
        @DisplayName("발급 요청 후 상태를 조회하면 REQUESTED 상태를 반환한다")
        void returnsRequestedStatus() {
            // given
            saveMember();
            Coupon coupon = saveLimitedCoupon(100);

            // 발급 요청
            ParameterizedTypeReference<ApiResponse<CouponIssueV1Dto.IssueStatusResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            testRestTemplate.exchange(
                    ENDPOINT_ISSUE_REQUEST.replace("{couponId}", coupon.getId().toString()),
                    HttpMethod.POST,
                    new HttpEntity<>(memberAuthHeaders()),
                    responseType);

            // when — 상태 조회
            ResponseEntity<ApiResponse<CouponIssueV1Dto.IssueStatusResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ISSUE_STATUS.replace("{couponId}", coupon.getId().toString()),
                    HttpMethod.GET,
                    new HttpEntity<>(memberAuthHeaders()),
                    responseType);

            // then
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().status()).isEqualTo(MemberCouponStatus.REQUESTED)
            );
        }

        @Test
        @DisplayName("요청 기록이 없으면 404 Not Found를 반환한다")
        void returns404_whenNotRequested() {
            // given
            saveMember();
            Coupon coupon = saveLimitedCoupon(100);

            // when
            ResponseEntity<String> response = testRestTemplate.exchange(
                    ENDPOINT_ISSUE_STATUS.replace("{couponId}", coupon.getId().toString()),
                    HttpMethod.GET,
                    new HttpEntity<>(memberAuthHeaders()),
                    String.class);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("인증 없이 조회하면 401 Unauthorized를 반환한다")
        void returns401_whenNoAuth() {
            // given
            Coupon coupon = saveLimitedCoupon(100);

            // when
            ResponseEntity<String> response = testRestTemplate.exchange(
                    ENDPOINT_ISSUE_STATUS.replace("{couponId}", coupon.getId().toString()),
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    String.class);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
