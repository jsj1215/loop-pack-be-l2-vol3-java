package com.loopers.interfaces.api;

import com.loopers.domain.member.BirthDate;
import com.loopers.domain.member.Email;
import com.loopers.domain.member.LoginId;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberName;
import com.loopers.domain.point.Point;
import com.loopers.infrastructure.member.MemberJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.interfaces.api.point.dto.PointV1Dto;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/*
  [E2E 테스트]

  대상 : 어드민 포인트 API 전체 흐름

  테스트 범위: HTTP 요청 -> Controller -> Facade -> Service -> Repository -> Database
  사용 라이브러리 : JUnit 5, Spring Boot Test, TestRestTemplate, Testcontainers, AssertJ
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminPointV1ApiE2ETest {

    private static final String ENDPOINT_ADMIN_POINTS = "/api-admin/v1/points";
    private static final String HEADER_ADMIN_LDAP = "X-Loopers-Ldap";
    private static final String ADMIN_LDAP_VALUE = "loopers.admin";

    private final TestRestTemplate testRestTemplate;
    private final MemberJpaRepository memberJpaRepository;
    private final PointJpaRepository pointJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public AdminPointV1ApiE2ETest(
            TestRestTemplate testRestTemplate,
            MemberJpaRepository memberJpaRepository,
            PointJpaRepository pointJpaRepository,
            DatabaseCleanUp databaseCleanUp,
            PasswordEncoder passwordEncoder) {
        this.testRestTemplate = testRestTemplate;
        this.memberJpaRepository = memberJpaRepository;
        this.pointJpaRepository = pointJpaRepository;
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

    private Member saveMember() {
        Member member = new Member(
                new LoginId("testuser1"),
                passwordEncoder.encode("Password1!"),
                new MemberName("홍길동"),
                new Email("test@example.com"),
                new BirthDate("19900101"));
        return memberJpaRepository.save(member);
    }

    private Point savePoint(Long memberId) {
        Point point = Point.create(memberId, 0);
        return pointJpaRepository.save(point);
    }

    @DisplayName("POST /api-admin/v1/points")
    @Nested
    class ChargePoint {

        @Test
        @DisplayName("유효한 요청이면, 200 OK를 반환하고 포인트가 충전된다.")
        void success_whenValidRequest() {
            // arrange
            Member member = saveMember();
            savePoint(member.getId());

            PointV1Dto.ChargePointRequest request = new PointV1Dto.ChargePointRequest(
                    member.getId(), 10000, "관리자 충전");

            // act
            ParameterizedTypeReference<ApiResponse<Void>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_POINTS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> {
                        Point updatedPoint = pointJpaRepository
                                .findByMemberIdAndDeletedAtIsNull(member.getId())
                                .orElseThrow();
                        assertThat(updatedPoint.getBalance()).isEqualTo(10000);
                    });
        }

        @Test
        @DisplayName("어드민 인증 없이 요청하면, 401 UNAUTHORIZED를 반환한다.")
        void fail_whenNoAdminAuth() {
            // arrange
            Member member = saveMember();
            savePoint(member.getId());

            PointV1Dto.ChargePointRequest request = new PointV1Dto.ChargePointRequest(
                    member.getId(), 10000, "관리자 충전");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // act
            ParameterizedTypeReference<ApiResponse<Void>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_POINTS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("충전 금액이 0 이하이면, 400 BAD_REQUEST를 반환한다.")
        void fail_whenAmountIsZeroOrNegative() {
            // arrange
            Member member = saveMember();
            savePoint(member.getId());

            PointV1Dto.ChargePointRequest request = new PointV1Dto.ChargePointRequest(
                    member.getId(), 0, "잘못된 충전");

            // act
            ParameterizedTypeReference<ApiResponse<Void>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_POINTS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                    () -> assertThat(response.getBody().meta().message()).contains("0보다 커야"));
        }
    }
}
