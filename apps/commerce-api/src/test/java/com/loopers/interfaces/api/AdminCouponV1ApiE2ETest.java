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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/*
  [E2E 테스트]

  대상 : 어드민 쿠폰 API 전체 흐름

  테스트 범위: HTTP 요청 → Controller → Facade → Service → Repository → Database
  사용 라이브러리 : JUnit 5, Spring Boot Test, TestRestTemplate, Testcontainers, AssertJ
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminCouponV1ApiE2ETest {

    private static final String ENDPOINT_ADMIN_COUPONS = "/api-admin/v1/coupons";
    private static final String HEADER_ADMIN_LDAP = "X-Loopers-Ldap";
    private static final String ADMIN_LDAP_VALUE = "loopers.admin";

    private final TestRestTemplate testRestTemplate;
    private final CouponJpaRepository couponJpaRepository;
    private final MemberCouponJpaRepository memberCouponJpaRepository;
    private final MemberJpaRepository memberJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public AdminCouponV1ApiE2ETest(
            TestRestTemplate testRestTemplate,
            CouponJpaRepository couponJpaRepository,
            MemberCouponJpaRepository memberCouponJpaRepository,
            MemberJpaRepository memberJpaRepository,
            DatabaseCleanUp databaseCleanUp,
            PasswordEncoder passwordEncoder) {
        this.testRestTemplate = testRestTemplate;
        this.couponJpaRepository = couponJpaRepository;
        this.memberCouponJpaRepository = memberCouponJpaRepository;
        this.memberJpaRepository = memberJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
        this.passwordEncoder = passwordEncoder;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_ADMIN_LDAP, ADMIN_LDAP_VALUE);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Coupon saveCoupon() {
        Coupon coupon = new Coupon(
                "테스트쿠폰",
                CouponScope.CART,
                null,
                DiscountType.FIXED_AMOUNT,
                1000,
                5000,
                0,
                ZonedDateTime.now().minusDays(1),
                ZonedDateTime.now().plusDays(30));
        return couponJpaRepository.save(coupon);
    }

    @DisplayName("POST /api-admin/v1/coupons")
    @Nested
    class CreateCoupon {

        @Test
        @DisplayName("유효한 요청이면, 201 CREATED와 쿠폰 정보를 반환한다.")
        void success_whenValidRequest() {
            // arrange
            CouponV1Dto.CreateCouponRequest request = new CouponV1Dto.CreateCouponRequest(
                    "신규쿠폰",
                    CouponScope.CART,
                    null,
                    DiscountType.FIXED_AMOUNT,
                    2000,
                    10000,
                    0,
                    ZonedDateTime.now().minusDays(1),
                    ZonedDateTime.now().plusDays(30));

            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_COUPONS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("신규쿠폰"),
                    () -> assertThat(response.getBody().data().id()).isNotNull());
        }

        @Test
        @DisplayName("어드민 인증 없이 요청하면, 401 UNAUTHORIZED를 반환한다.")
        void fail_whenNoAdminAuth() {
            // arrange
            CouponV1Dto.CreateCouponRequest request = new CouponV1Dto.CreateCouponRequest(
                    "신규쿠폰",
                    CouponScope.CART,
                    null,
                    DiscountType.FIXED_AMOUNT,
                    2000,
                    10000,
                    0,
                    ZonedDateTime.now().minusDays(1),
                    ZonedDateTime.now().plusDays(30));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_COUPONS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @DisplayName("GET /api-admin/v1/coupons")
    @Nested
    class GetCoupons {

        @Test
        @DisplayName("쿠폰 목록을 조회하면, 200 OK와 페이지 정보를 반환한다.")
        void success_whenGetCoupons() {
            // arrange
            saveCoupon();

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_COUPONS + "?page=0&size=10",
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data()).isNotNull());
        }
    }

    @DisplayName("GET /api-admin/v1/coupons/{couponId}")
    @Nested
    class GetCoupon {

        @Test
        @DisplayName("존재하는 쿠폰을 조회하면, 200 OK와 쿠폰 상세 정보를 반환한다.")
        void success_whenCouponExists() {
            // arrange
            Coupon coupon = saveCoupon();

            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_COUPONS + "/" + coupon.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("테스트쿠폰"));
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰을 조회하면, 404 NOT_FOUND를 반환한다.")
        void fail_whenCouponNotFound() {
            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_COUPONS + "/999",
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("PUT /api-admin/v1/coupons/{couponId}")
    @Nested
    class UpdateCoupon {

        @Test
        @DisplayName("유효한 요청이면, 200 OK와 수정된 쿠폰 정보를 반환한다.")
        void success_whenValidRequest() {
            // arrange
            Coupon coupon = saveCoupon();
            CouponV1Dto.UpdateCouponRequest request = new CouponV1Dto.UpdateCouponRequest(
                    "수정된쿠폰",
                    CouponScope.PRODUCT,
                    100L,
                    DiscountType.FIXED_RATE,
                    10,
                    5000,
                    3000,
                    ZonedDateTime.now().minusDays(1),
                    ZonedDateTime.now().plusDays(60));

            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_COUPONS + "/" + coupon.getId(),
                    HttpMethod.PUT,
                    new HttpEntity<>(request, adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("수정된쿠폰"));
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰을 수정하면, 404 NOT_FOUND를 반환한다.")
        void fail_whenCouponNotFound() {
            // arrange
            CouponV1Dto.UpdateCouponRequest request = new CouponV1Dto.UpdateCouponRequest(
                    "수정된쿠폰",
                    CouponScope.CART,
                    null,
                    DiscountType.FIXED_AMOUNT,
                    1000,
                    5000,
                    0,
                    ZonedDateTime.now().minusDays(1),
                    ZonedDateTime.now().plusDays(30));

            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_COUPONS + "/999",
                    HttpMethod.PUT,
                    new HttpEntity<>(request, adminHeaders()),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("DELETE /api-admin/v1/coupons/{couponId}")
    @Nested
    class DeleteCoupon {

        @Test
        @DisplayName("존재하는 쿠폰을 삭제하면, 200 OK를 반환한다.")
        void success_whenCouponExists() {
            // arrange
            Coupon coupon = saveCoupon();

            // act
            ParameterizedTypeReference<ApiResponse<Void>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_COUPONS + "/" + coupon.getId(),
                    HttpMethod.DELETE,
                    new HttpEntity<>(adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(couponJpaRepository.findByIdAndDeletedAtIsNull(coupon.getId())).isEmpty());
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰을 삭제하면, 404 NOT_FOUND를 반환한다.")
        void fail_whenCouponNotFound() {
            // act
            ParameterizedTypeReference<ApiResponse<Void>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_COUPONS + "/999",
                    HttpMethod.DELETE,
                    new HttpEntity<>(adminHeaders()),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("GET /api-admin/v1/coupons/{couponId}/issues")
    @Nested
    class GetCouponIssues {

        @Test
        @DisplayName("쿠폰 발급 내역을 조회하면, 200 OK와 발급 내역을 반환한다.")
        void success_whenCouponExists() {
            // arrange
            Coupon coupon = saveCoupon();
            Member member = saveMember();
            MemberCoupon memberCoupon = new MemberCoupon(member.getId(), coupon.getId());
            memberCouponJpaRepository.save(memberCoupon);

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_COUPONS + "/" + coupon.getId() + "/issues?page=0&size=20",
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data()).isNotNull());
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰의 발급 내역을 조회하면, 404 NOT_FOUND를 반환한다.")
        void fail_whenCouponNotFound() {
            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_COUPONS + "/999/issues?page=0&size=20",
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
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
}
