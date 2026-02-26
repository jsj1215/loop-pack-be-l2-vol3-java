package com.loopers.interfaces.api;

import com.loopers.domain.member.BirthDate;
import com.loopers.domain.member.Email;
import com.loopers.domain.member.LoginId;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberName;
import com.loopers.domain.point.PointService;
import com.loopers.infrastructure.member.MemberJpaRepository;
import com.loopers.interfaces.api.member.dto.MemberV1Dto;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/*
  [E2E 테스트]

  대상 : 회원가입 API 전체 흐름

  테스트 범위: HTTP 요청 → Controller → Facade → Service → Repository → Database
  사용 라이브러리 : JUnit 5, Spring Boot Test, TestRestTemplate, Testcontainers, AssertJ
 
  어노테이션 설명:
  - @SpringBootTest(webEnvironment = RANDOM_PORT): 실제 웹 서버 구동
  → RANDOM_PORT: 사용 가능한 임의의 포트에서 실제 HTTP 서버 시작
  → MockMvc와 달리 실제 HTTP 통신 수행
 
  - TestRestTemplate: 실제 HTTP 요청을 보내는 테스트 클라이언트
  → exchange(): HTTP 요청 전송 및 응답 수신
  → 실제 네트워크를 통한 요청/응답 테스트
  → 응답 헤더, 상태 코드, 본문 모두 검증 가능
 
  - ParameterizedTypeReference: 제네릭 타입의 응답 바디 역직렬화
  → ApiResponse<T> 같은 제네릭 응답 처리
 
  특징:
  - 전체 애플리케이션 스택 테스트 (사용자 관점)
  - 실제 HTTP 서버 구동 + 실제 DB 연동
  - Docker Daemon 필수 (Testcontainers)
  - 가장 현실적인 테스트 but 가장 느림
  - 응답 헤더(X-Loopers-LoginId 등) 검증 가능
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MemberV1ApiE2ETest {

        private static final String ENDPOINT_SIGNUP = "/api/v1/members/signup";
        private static final String ENDPOINT_ME = "/api/v1/members/me";
        private static final String ENDPOINT_CHANGE_PASSWORD = "/api/v1/members/me/password";
        private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
        private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

        private final TestRestTemplate testRestTemplate;
        private final MemberJpaRepository memberJpaRepository;
        private final DatabaseCleanUp databaseCleanUp;
        private final PasswordEncoder passwordEncoder;
        private final PointService pointService;

        @Autowired
        public MemberV1ApiE2ETest(
                        TestRestTemplate testRestTemplate,
                        MemberJpaRepository memberJpaRepository,
                        DatabaseCleanUp databaseCleanUp,
                        PasswordEncoder passwordEncoder,
                        PointService pointService) {
                this.testRestTemplate = testRestTemplate;
                this.memberJpaRepository = memberJpaRepository;
                this.databaseCleanUp = databaseCleanUp;
                this.passwordEncoder = passwordEncoder;
                this.pointService = pointService;
        }

        @AfterEach
        void tearDown() {
                databaseCleanUp.truncateAllTables();
        }

        private Member createMember(String loginId, String encodedPassword, String name, String email, String birthDate) {
                return new Member(
                        new LoginId(loginId),
                        encodedPassword,
                        new MemberName(name),
                        new Email(email),
                        new BirthDate(birthDate));
        }

        private Member saveMemberWithPoint(String loginId, String encodedPassword, String name, String email, String birthDate) {
                Member member = createMember(loginId, encodedPassword, name, email, birthDate);
                Member savedMember = memberJpaRepository.save(member);
                pointService.createPoint(savedMember.getId());
                return savedMember;
        }

        @DisplayName("POST /api/v1/members/signup")
        @Nested
        class Signup {

                @Test
                @DisplayName("유효한 요청이면, 200 OK와 회원 ID를 반환하고 헤더에 로그인 ID만 포함되고 비밀번호는 포함되지 않는다.")
                void success_whenValidRequest() {
                        // arrange
                        MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                                        "testuser1",
                                        "Password1!",
                                        "홍길동",
                                        "test@example.com",
                                        "19990101");

                        // act
                        ParameterizedTypeReference<ApiResponse<MemberV1Dto.SignupResponse>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<MemberV1Dto.SignupResponse>> response = testRestTemplate.exchange(
                                        ENDPOINT_SIGNUP,
                                        HttpMethod.POST,
                                        new HttpEntity<>(request),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                                        () -> assertThat(response.getBody()).isNotNull(),
                                        () -> assertThat(response.getBody().data().memberId()).isNotNull(),
                                        () -> assertThat(response.getHeaders().getFirst("X-Loopers-LoginId"))
                                                        .isEqualTo("testuser1"),
                                        () -> assertThat(response.getHeaders().getFirst("X-Loopers-LoginPw"))
                                                        .isNull());
                }

                @Test
                @DisplayName("로그인 ID가 형식에 맞지 않으면, 400 BAD_REQUEST를 반환한다.")
                void fail_whenInvalidLoginId() {
                        // arrange
                        MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                                        "ab", // 4자 미만
                                        "Password1!",
                                        "홍길동",
                                        "test@example.com",
                                        "19990101");

                        // act
                        ParameterizedTypeReference<ApiResponse<MemberV1Dto.SignupResponse>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<MemberV1Dto.SignupResponse>> response = testRestTemplate.exchange(
                                        ENDPOINT_SIGNUP,
                                        HttpMethod.POST,
                                        new HttpEntity<>(request),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                                        () -> assertThat(response.getBody().meta().message()).contains("4", "20"));
                }

                @Test
                @DisplayName("비밀번호에 생년월일이 포함되면, 400 BAD_REQUEST를 반환한다.")
                void fail_whenPasswordContainsBirthDate() {
                        // arrange
                        MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                                        "testuser1",
                                        "Pass19990101!", // 생년월일 포함
                                        "홍길동",
                                        "test@example.com",
                                        "19990101");

                        // act
                        ParameterizedTypeReference<ApiResponse<MemberV1Dto.SignupResponse>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<MemberV1Dto.SignupResponse>> response = testRestTemplate.exchange(
                                        ENDPOINT_SIGNUP,
                                        HttpMethod.POST,
                                        new HttpEntity<>(request),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                                        () -> assertThat(response.getBody().meta().message()).contains("생년월일"));
                }

                @Test
                @DisplayName("중복된 로그인 ID이면, 409 CONFLICT를 반환한다.")
                void fail_whenDuplicateLoginId() {
                        // arrange
                        Member existingMember = createMember(
                                        "testuser1",
                                        passwordEncoder.encode("Password1!"),
                                        "기존유저",
                                        "existing@example.com",
                                        "19900101");
                        memberJpaRepository.save(existingMember);

                        MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                                        "testuser1", // 중복된 ID
                                        "Password2!",
                                        "홍길동",
                                        "test@example.com",
                                        "19990101");

                        // act
                        ParameterizedTypeReference<ApiResponse<MemberV1Dto.SignupResponse>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<MemberV1Dto.SignupResponse>> response = testRestTemplate.exchange(
                                        ENDPOINT_SIGNUP,
                                        HttpMethod.POST,
                                        new HttpEntity<>(request),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                                        () -> assertThat(response.getBody().meta().message())
                                                        .contains("이미 사용 중인 로그인 아이디"));
                }

                @Test
                @DisplayName("이름이 형식에 맞지 않으면, 400 BAD_REQUEST를 반환한다.")
                void fail_whenInvalidName() {
                        // arrange
                        MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                                        "testuser1",
                                        "Password1!",
                                        "Hong", // 한글이 아님
                                        "test@example.com",
                                        "19990101");

                        // act
                        ParameterizedTypeReference<ApiResponse<MemberV1Dto.SignupResponse>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<MemberV1Dto.SignupResponse>> response = testRestTemplate.exchange(
                                        ENDPOINT_SIGNUP,
                                        HttpMethod.POST,
                                        new HttpEntity<>(request),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                                        () -> assertThat(response.getBody().meta().message()).contains("한글"));
                }

                @Test
                @DisplayName("이메일이 형식에 맞지 않으면, 400 BAD_REQUEST를 반환한다.")
                void fail_whenInvalidEmail() {
                        // arrange
                        MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                                        "testuser1",
                                        "Password1!",
                                        "홍길동",
                                        "invalid-email", // 이메일 형식 아님
                                        "19990101");

                        // act
                        ParameterizedTypeReference<ApiResponse<MemberV1Dto.SignupResponse>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<MemberV1Dto.SignupResponse>> response = testRestTemplate.exchange(
                                        ENDPOINT_SIGNUP,
                                        HttpMethod.POST,
                                        new HttpEntity<>(request),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                                        () -> assertThat(response.getBody().meta().message()).contains("이메일"));
                }

                @Test
                @DisplayName("생년월일이 형식에 맞지 않으면, 400 BAD_REQUEST를 반환한다.")
                void fail_whenInvalidBirthDate() {
                        // arrange
                        MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                                        "testuser1",
                                        "Password1!",
                                        "홍길동",
                                        "test@example.com",
                                        "1992-12-15" // 형식 오류 (하이픈 포함)
                        );

                        // act
                        ParameterizedTypeReference<ApiResponse<MemberV1Dto.SignupResponse>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<MemberV1Dto.SignupResponse>> response = testRestTemplate.exchange(
                                        ENDPOINT_SIGNUP,
                                        HttpMethod.POST,
                                        new HttpEntity<>(request),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                                        () -> assertThat(response.getBody().meta().message()).contains("8자리"));
                }

                @Test
                @DisplayName("중복된 이메일이면, 409 CONFLICT를 반환한다.")
                void fail_whenDuplicateEmail() {
                        // arrange
                        Member existingMember = createMember(
                                        "existing1",
                                        passwordEncoder.encode("Password1!"),
                                        "기존유저",
                                        "test@example.com",
                                        "19900101");
                        memberJpaRepository.save(existingMember);

                        MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                                        "testuser1",
                                        "Password2!",
                                        "홍길동",
                                        "test@example.com", // 중복된 이메일
                                        "19990101");

                        // act
                        ParameterizedTypeReference<ApiResponse<MemberV1Dto.SignupResponse>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<MemberV1Dto.SignupResponse>> response = testRestTemplate.exchange(
                                        ENDPOINT_SIGNUP,
                                        HttpMethod.POST,
                                        new HttpEntity<>(request),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                                        () -> assertThat(response.getBody().meta().message()).contains("이메일"));
                }

                @Test
                @DisplayName("생년월일이 미래 날짜이면, 400 BAD_REQUEST를 반환한다.")
                void fail_whenFutureBirthDate() {
                        // arrange
                        MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                                        "testuser1",
                                        "Password1!",
                                        "홍길동",
                                        "test@example.com",
                                        "29991231" // 미래 날짜
                        );

                        // act
                        ParameterizedTypeReference<ApiResponse<MemberV1Dto.SignupResponse>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<MemberV1Dto.SignupResponse>> response = testRestTemplate.exchange(
                                        ENDPOINT_SIGNUP,
                                        HttpMethod.POST,
                                        new HttpEntity<>(request),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                                        () -> assertThat(response.getBody().meta().message()).contains("미래"));
                }
        }

        @DisplayName("GET /api/v1/members/me")
        @Nested
        class GetMyInfo {

                @Test
                @DisplayName("유효한 인증 정보면, 200 OK와 마스킹된 내 정보를 반환한다.")
                void success_whenValidAuth() {
                        // arrange - 회원 생성
                        Member member = saveMemberWithPoint(
                                        "testuser1",
                                        passwordEncoder.encode("Password1!"),
                                        "홍길동",
                                        "test@example.com",
                                        "19900101");

                        HttpHeaders headers = new HttpHeaders();
                        headers.set(HEADER_LOGIN_ID, "testuser1");
                        headers.set(HEADER_LOGIN_PW, "Password1!");

                        // act
                        ParameterizedTypeReference<ApiResponse<MemberV1Dto.MyInfoResponse>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<MemberV1Dto.MyInfoResponse>> response = testRestTemplate.exchange(
                                        ENDPOINT_ME,
                                        HttpMethod.GET,
                                        new HttpEntity<>(headers),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                                        () -> assertThat(response.getBody()).isNotNull(),
                                        () -> assertThat(response.getBody().data().loginId()).isEqualTo("testuser1"),
                                        () -> assertThat(response.getBody().data().name()).isEqualTo("홍길*"), // 마스킹
                                        () -> assertThat(response.getBody().data().email())
                                                        .isEqualTo("test@example.com"),
                                        () -> assertThat(response.getBody().data().birthDate()).isEqualTo("19900101"));
                }

                @Test
                @DisplayName("2글자 이름인 경우, 마지막 글자가 마스킹된다.")
                void masksLastCharacter_when2CharacterName() {
                        // arrange - 2글자 이름 회원 생성
                        Member member = saveMemberWithPoint(
                                        "testuser1",
                                        passwordEncoder.encode("Password1!"),
                                        "홍길",
                                        "test@example.com",
                                        "19900101");

                        HttpHeaders headers = new HttpHeaders();
                        headers.set(HEADER_LOGIN_ID, "testuser1");
                        headers.set(HEADER_LOGIN_PW, "Password1!");

                        // act
                        ParameterizedTypeReference<ApiResponse<MemberV1Dto.MyInfoResponse>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<MemberV1Dto.MyInfoResponse>> response = testRestTemplate.exchange(
                                        ENDPOINT_ME,
                                        HttpMethod.GET,
                                        new HttpEntity<>(headers),
                                        responseType);

                        // assert
                        assertThat(response.getBody().data().name()).isEqualTo("홍*");
                }

                @Test
                @DisplayName("인증 헤더가 없으면, 401 UNAUTHORIZED를 반환한다.")
                void fail_whenNoAuthHeaders() {
                        // act
                        ParameterizedTypeReference<ApiResponse<MemberV1Dto.MyInfoResponse>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<MemberV1Dto.MyInfoResponse>> response = testRestTemplate.exchange(
                                        ENDPOINT_ME,
                                        HttpMethod.GET,
                                        null,
                                        responseType);

                        // assert
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                }

                @Test
                @DisplayName("로그인 ID가 존재하지 않으면, 401 UNAUTHORIZED를 반환한다.")
                void fail_whenLoginIdNotFound() {
                        // arrange
                        HttpHeaders headers = new HttpHeaders();
                        headers.set(HEADER_LOGIN_ID, "nonexistent");
                        headers.set(HEADER_LOGIN_PW, "Password1!");

                        // act
                        ParameterizedTypeReference<ApiResponse<MemberV1Dto.MyInfoResponse>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<MemberV1Dto.MyInfoResponse>> response = testRestTemplate.exchange(
                                        ENDPOINT_ME,
                                        HttpMethod.GET,
                                        new HttpEntity<>(headers),
                                        responseType);

                        // assert
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                }

                @Test
                @DisplayName("비밀번호가 일치하지 않으면, 401 UNAUTHORIZED를 반환한다.")
                void fail_whenPasswordNotMatches() {
                        // arrange - 회원 생성
                        Member member = createMember(
                                        "testuser1",
                                        passwordEncoder.encode("Password1!"),
                                        "홍길동",
                                        "test@example.com",
                                        "19900101");
                        memberJpaRepository.save(member);

                        HttpHeaders headers = new HttpHeaders();
                        headers.set(HEADER_LOGIN_ID, "testuser1");
                        headers.set(HEADER_LOGIN_PW, "WrongPassword!");

                        // act
                        ParameterizedTypeReference<ApiResponse<MemberV1Dto.MyInfoResponse>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<MemberV1Dto.MyInfoResponse>> response = testRestTemplate.exchange(
                                        ENDPOINT_ME,
                                        HttpMethod.GET,
                                        new HttpEntity<>(headers),
                                        responseType);

                        // assert
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                }
        }

        @DisplayName("PATCH /api/v1/members/me/password")
        @Nested
        class ChangePassword {

                @Test
                @DisplayName("유효한 요청이면, 200 OK를 반환하고 응답 헤더에 비밀번호가 포함되지 않는다.")
                void success_whenValidRequest() {
                        // arrange - 회원 생성
                        Member member = createMember(
                                        "testuser1",
                                        passwordEncoder.encode("Password1!"),
                                        "홍길동",
                                        "test@example.com",
                                        "19900101");
                        memberJpaRepository.save(member);

                        HttpHeaders headers = new HttpHeaders();
                        headers.set(HEADER_LOGIN_ID, "testuser1");
                        headers.set(HEADER_LOGIN_PW, "Password1!");
                        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

                        MemberV1Dto.ChangePasswordRequest request = new MemberV1Dto.ChangePasswordRequest(
                                        "Password1!",
                                        "NewPass123!");

                        // act
                        ParameterizedTypeReference<ApiResponse<Void>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                                        ENDPOINT_CHANGE_PASSWORD,
                                        HttpMethod.PATCH,
                                        new HttpEntity<>(request, headers),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                                        () -> assertThat(response.getHeaders().getFirst(HEADER_LOGIN_PW))
                                                        .isNull());

                        // 변경된 비밀번호로 인증 확인
                        Member updatedMember = memberJpaRepository.findByLoginId("testuser1").orElseThrow();
                        assertThat(passwordEncoder.matches("NewPass123!", updatedMember.getPassword())).isTrue();
                }

                @Test
                @DisplayName("인증 헤더가 없으면, 401 UNAUTHORIZED를 반환한다.")
                void fail_whenNoAuthHeaders() {
                        // arrange
                        MemberV1Dto.ChangePasswordRequest request = new MemberV1Dto.ChangePasswordRequest(
                                        "Password1!",
                                        "NewPass123!");

                        HttpHeaders headers = new HttpHeaders();
                        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

                        // act
                        ParameterizedTypeReference<ApiResponse<Void>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                                        ENDPOINT_CHANGE_PASSWORD,
                                        HttpMethod.PATCH,
                                        new HttpEntity<>(request, headers),
                                        responseType);

                        // assert
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                }

                @Test
                @DisplayName("헤더 인증에 실패하면, 401 UNAUTHORIZED를 반환한다.")
                void fail_whenHeaderAuthFails() {
                        // arrange - 회원 생성
                        Member member = createMember(
                                        "testuser1",
                                        passwordEncoder.encode("Password1!"),
                                        "홍길동",
                                        "test@example.com",
                                        "19900101");
                        memberJpaRepository.save(member);

                        HttpHeaders headers = new HttpHeaders();
                        headers.set(HEADER_LOGIN_ID, "testuser1");
                        headers.set(HEADER_LOGIN_PW, "WrongPassword!"); // 틀린 비밀번호
                        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

                        MemberV1Dto.ChangePasswordRequest request = new MemberV1Dto.ChangePasswordRequest(
                                        "Password1!",
                                        "NewPass123!");

                        // act
                        ParameterizedTypeReference<ApiResponse<Void>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                                        ENDPOINT_CHANGE_PASSWORD,
                                        HttpMethod.PATCH,
                                        new HttpEntity<>(request, headers),
                                        responseType);

                        // assert
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                }

                @Test
                @DisplayName("기존 비밀번호가 일치하지 않으면, 400 BAD_REQUEST를 반환한다.")
                void fail_whenCurrentPasswordNotMatches() {
                        // arrange - 회원 생성
                        Member member = createMember(
                                        "testuser1",
                                        passwordEncoder.encode("Password1!"),
                                        "홍길동",
                                        "test@example.com",
                                        "19900101");
                        memberJpaRepository.save(member);

                        HttpHeaders headers = new HttpHeaders();
                        headers.set(HEADER_LOGIN_ID, "testuser1");
                        headers.set(HEADER_LOGIN_PW, "Password1!");
                        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

                        MemberV1Dto.ChangePasswordRequest request = new MemberV1Dto.ChangePasswordRequest(
                                        "WrongCurrent!", // 틀린 기존 비밀번호
                                        "NewPass123!");

                        // act
                        ParameterizedTypeReference<ApiResponse<Void>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                                        ENDPOINT_CHANGE_PASSWORD,
                                        HttpMethod.PATCH,
                                        new HttpEntity<>(request, headers),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                                        () -> assertThat(response.getBody().meta().message()).contains("기존 비밀번호"));
                }

                @Test
                @DisplayName("새 비밀번호가 현재 비밀번호와 동일하면, 400 BAD_REQUEST를 반환한다.")
                void fail_whenNewPasswordSameAsCurrent() {
                        // arrange - 회원 생성
                        Member member = createMember(
                                        "testuser1",
                                        passwordEncoder.encode("Password1!"),
                                        "홍길동",
                                        "test@example.com",
                                        "19900101");
                        memberJpaRepository.save(member);

                        HttpHeaders headers = new HttpHeaders();
                        headers.set(HEADER_LOGIN_ID, "testuser1");
                        headers.set(HEADER_LOGIN_PW, "Password1!");
                        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

                        MemberV1Dto.ChangePasswordRequest request = new MemberV1Dto.ChangePasswordRequest(
                                        "Password1!",
                                        "Password1!" // 현재 비밀번호와 동일
                        );

                        // act
                        ParameterizedTypeReference<ApiResponse<Void>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                                        ENDPOINT_CHANGE_PASSWORD,
                                        HttpMethod.PATCH,
                                        new HttpEntity<>(request, headers),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                                        () -> assertThat(response.getBody().meta().message()).contains("현재 비밀번호"));
                }

                @Test
                @DisplayName("새 비밀번호에 생년월일이 포함되면, 400 BAD_REQUEST를 반환한다.")
                void fail_whenNewPasswordContainsBirthDate() {
                        // arrange - 회원 생성
                        Member member = createMember(
                                        "testuser1",
                                        passwordEncoder.encode("Password1!"),
                                        "홍길동",
                                        "test@example.com",
                                        "19900101");
                        memberJpaRepository.save(member);

                        HttpHeaders headers = new HttpHeaders();
                        headers.set(HEADER_LOGIN_ID, "testuser1");
                        headers.set(HEADER_LOGIN_PW, "Password1!");
                        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

                        MemberV1Dto.ChangePasswordRequest request = new MemberV1Dto.ChangePasswordRequest(
                                        "Password1!",
                                        "Pass19900101!" // 생년월일 포함
                        );

                        // act
                        ParameterizedTypeReference<ApiResponse<Void>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                                        ENDPOINT_CHANGE_PASSWORD,
                                        HttpMethod.PATCH,
                                        new HttpEntity<>(request, headers),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                                        () -> assertThat(response.getBody().meta().message()).contains("생년월일"));
                }

                @Test
                @DisplayName("새 비밀번호가 유효성 검사에 실패하면, 400 BAD_REQUEST를 반환한다.")
                void fail_whenNewPasswordInvalid() {
                        // arrange - 회원 생성
                        Member member = createMember(
                                        "testuser1",
                                        passwordEncoder.encode("Password1!"),
                                        "홍길동",
                                        "test@example.com",
                                        "19900101");
                        memberJpaRepository.save(member);

                        HttpHeaders headers = new HttpHeaders();
                        headers.set(HEADER_LOGIN_ID, "testuser1");
                        headers.set(HEADER_LOGIN_PW, "Password1!");
                        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

                        MemberV1Dto.ChangePasswordRequest request = new MemberV1Dto.ChangePasswordRequest(
                                        "Password1!",
                                        "short" // 8자 미만
                        );

                        // act
                        ParameterizedTypeReference<ApiResponse<Void>> responseType = new ParameterizedTypeReference<>() {
                        };
                        ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                                        ENDPOINT_CHANGE_PASSWORD,
                                        HttpMethod.PATCH,
                                        new HttpEntity<>(request, headers),
                                        responseType);

                        // assert
                        assertAll(
                                        () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                                        () -> assertThat(response.getBody().meta().message()).contains("8", "16"));
                }
        }
}
