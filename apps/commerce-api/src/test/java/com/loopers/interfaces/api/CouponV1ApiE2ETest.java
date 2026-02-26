package com.loopers.interfaces.api;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.DiscountType;
import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.member.BirthDate;
import com.loopers.domain.member.Email;
import com.loopers.domain.member.LoginId;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberName;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.infrastructure.coupon.MemberCouponJpaRepository;
import com.loopers.infrastructure.member.MemberJpaRepository;
import com.loopers.interfaces.api.coupon.dto.CouponV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/*
  [E2E 테스트]

  대상 : 쿠폰 고객 API 전체 흐름

  테스트 범위: HTTP 요청 → Controller → Facade → Service → Repository → Database
  사용 라이브러리 : JUnit 5, Spring Boot Test, TestRestTemplate, Testcontainers, AssertJ
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponV1ApiE2ETest {

    private static final String ENDPOINT_COUPONS = "/api/v1/coupons";
    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

    private final TestRestTemplate testRestTemplate;
    private final MemberJpaRepository memberJpaRepository;
    private final CouponJpaRepository couponJpaRepository;
    private final MemberCouponJpaRepository memberCouponJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public CouponV1ApiE2ETest(
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

    private Member saveMember() {
        Member member = new Member(
                new LoginId("testuser1"),
                passwordEncoder.encode("Password1!"),
                new MemberName("홍길동"),
                new Email("test@example.com"),
                new BirthDate("19900101"));
        return memberJpaRepository.save(member);
    }

    private Coupon saveValidCoupon() {
        Coupon coupon = new Coupon(
                "테스트쿠폰",
                CouponScope.CART,
                null,
                DiscountType.FIXED_AMOUNT,
                1000,
                5000,
                0,
                100,
                ZonedDateTime.now().minusDays(1),
                ZonedDateTime.now().plusDays(30));
        return couponJpaRepository.save(coupon);
    }

    private HttpHeaders memberAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_LOGIN_ID, "testuser1");
        headers.set(HEADER_LOGIN_PW, "Password1!");
        return headers;
    }

    @DisplayName("GET /api/v1/coupons")
    @Nested
    class GetAvailableCoupons {

        @Test
        @DisplayName("다운로드 가능한 쿠폰 목록을 조회하면, 200 OK와 쿠폰 목록을 반환한다.")
        void success_whenGetCoupons() {
            // arrange
            saveValidCoupon();

            // act
            ParameterizedTypeReference<ApiResponse<List<CouponV1Dto.CouponResponse>>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<CouponV1Dto.CouponResponse>>> response = testRestTemplate.exchange(
                    ENDPOINT_COUPONS,
                    HttpMethod.GET,
                    null,
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data()).isNotEmpty(),
                    () -> assertThat(response.getBody().data().get(0).name()).isEqualTo("테스트쿠폰"));
        }
    }

    @DisplayName("POST /api/v1/coupons/{couponId}/download")
    @Nested
    class DownloadCoupon {

        @Test
        @DisplayName("인증된 사용자가 쿠폰을 다운로드하면, 200 OK와 쿠폰 정보를 반환한다.")
        void success_whenAuthenticated() {
            // arrange
            saveMember();
            Coupon coupon = saveValidCoupon();

            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_COUPONS + "/" + coupon.getId() + "/download",
                    HttpMethod.POST,
                    new HttpEntity<>(memberAuthHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("테스트쿠폰"));
        }

        @Test
        @DisplayName("인증 없이 쿠폰을 다운로드하면, 401 UNAUTHORIZED를 반환한다.")
        void fail_whenNoAuth() {
            // arrange
            Coupon coupon = saveValidCoupon();

            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_COUPONS + "/" + coupon.getId() + "/download",
                    HttpMethod.POST,
                    null,
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("이미 다운로드한 쿠폰을 다시 다운로드하면, 409 CONFLICT를 반환한다.")
        void fail_whenDuplicateDownload() {
            // arrange
            Member member = saveMember();
            Coupon coupon = saveValidCoupon();

            MemberCoupon memberCoupon = new MemberCoupon(member.getId(), coupon.getId());
            memberCouponJpaRepository.save(memberCoupon);

            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_COUPONS + "/" + coupon.getId() + "/download",
                    HttpMethod.POST,
                    new HttpEntity<>(memberAuthHeaders()),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @DisplayName("GET /api/v1/coupons/me")
    @Nested
    class GetMyCoupons {

        @Test
        @DisplayName("인증된 사용자가 내 쿠폰 목록을 조회하면, 200 OK와 쿠폰 목록을 반환한다.")
        void success_whenAuthenticated() {
            // arrange
            Member member = saveMember();
            Coupon coupon = saveValidCoupon();

            MemberCoupon memberCoupon = new MemberCoupon(member.getId(), coupon.getId());
            memberCouponJpaRepository.save(memberCoupon);

            // act
            ParameterizedTypeReference<ApiResponse<List<CouponV1Dto.MyCouponResponse>>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<CouponV1Dto.MyCouponResponse>>> response = testRestTemplate.exchange(
                    ENDPOINT_COUPONS + "/me",
                    HttpMethod.GET,
                    new HttpEntity<>(memberAuthHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data()).isNotEmpty());
        }

        @Test
        @DisplayName("인증 없이 내 쿠폰 목록을 조회하면, 401 UNAUTHORIZED를 반환한다.")
        void fail_whenNoAuth() {
            // act
            ParameterizedTypeReference<ApiResponse<List<CouponV1Dto.MyCouponResponse>>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<CouponV1Dto.MyCouponResponse>>> response = testRestTemplate.exchange(
                    ENDPOINT_COUPONS + "/me",
                    HttpMethod.GET,
                    null,
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
