package com.loopers.interfaces.api;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.DiscountType;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
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
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public AdminCouponV1ApiE2ETest(
            TestRestTemplate testRestTemplate,
            CouponJpaRepository couponJpaRepository,
            DatabaseCleanUp databaseCleanUp) {
        this.testRestTemplate = testRestTemplate;
        this.couponJpaRepository = couponJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
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
                100,
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
                    50,
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
                    50,
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
}
