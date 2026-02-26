package com.loopers.application.member;

import com.loopers.domain.member.BirthDate;
import com.loopers.domain.member.Email;
import com.loopers.domain.member.LoginId;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [단위 테스트 - Application DTO]
 *
 * 테스트 대상: MyInfo (Application Layer DTO)
 * 테스트 유형: 순수 단위 테스트 (Pure Unit Test)
 * 외부 의존성: 없음
 *
 * 사용 라이브러리:
 * - JUnit 5 (org.junit.jupiter)
 * - Mockito (org.mockito)
 * - AssertJ (org.assertj.core.api)
 *
 * 특징:
 * - Spring Context 불필요 → 빠른 실행
 * - Docker/DB 불필요
 * - Member 엔티티에서 MyInfo로의 변환 및 이름 마스킹 로직 검증
 */
@DisplayName("MyInfo")
class MyInfoTest {

    @Nested
    @DisplayName("Member로부터 생성할 때,")
    class FromMember {

        @Test
        @DisplayName("3글자 이름이 마스킹되어 매핑된다.")
        void masksName_when3CharacterName() {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getLoginId()).thenReturn("testuser1");
            when(mockMember.getName()).thenReturn("홍길동");
            when(mockMember.getEmail()).thenReturn("test@example.com");
            when(mockMember.getBirthDate()).thenReturn("19990101");

            // when
            MyInfo info = MyInfo.from(mockMember, 1000);

            // then
            assertAll(
                    () -> assertThat(info.loginId()).isEqualTo("testuser1"),
                    () -> assertThat(info.name()).isEqualTo("홍길*"),
                    () -> assertThat(info.email()).isEqualTo("test@example.com"),
                    () -> assertThat(info.birthDate()).isEqualTo("19990101"));
        }

        @Test
        @DisplayName("2글자 이름이 마스킹되어 매핑된다.")
        void masksName_when2CharacterName() {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getLoginId()).thenReturn("testuser1");
            when(mockMember.getName()).thenReturn("홍길");
            when(mockMember.getEmail()).thenReturn("test@example.com");
            when(mockMember.getBirthDate()).thenReturn("19990101");

            // when
            MyInfo info = MyInfo.from(mockMember, 1000);

            // then
            assertThat(info.name()).isEqualTo("홍*");
        }

        @Test
        @DisplayName("10글자 이름이 마스킹되어 매핑된다.")
        void masksName_when10CharacterName() {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getLoginId()).thenReturn("testuser1");
            when(mockMember.getName()).thenReturn("가나다라마바사아자차");
            when(mockMember.getEmail()).thenReturn("test@example.com");
            when(mockMember.getBirthDate()).thenReturn("19990101");

            // when
            MyInfo info = MyInfo.from(mockMember, 1000);

            // then
            assertThat(info.name()).isEqualTo("가나다라마바사아자*");
        }

        @Test
        @DisplayName("실제 Member 엔티티로부터 생성하면 필드가 올바르게 매핑된다.")
        void createsFromRealMember_withAllFieldsMapped() {
            // given
            Member member = new Member(
                    new LoginId("testuser1"),
                    "$2a$10$encodedPassword",
                    new MemberName("홍길동"),
                    new Email("test@example.com"),
                    new BirthDate("19990101"));

            // when
            MyInfo info = MyInfo.from(member, 5000);

            // then
            assertAll(
                    () -> assertThat(info.loginId()).isEqualTo("testuser1"),
                    () -> assertThat(info.name()).isEqualTo("홍길*"),
                    () -> assertThat(info.email()).isEqualTo("test@example.com"),
                    () -> assertThat(info.birthDate()).isEqualTo("19990101"),
                    () -> assertThat(info.pointBalance()).isEqualTo(5000));
        }
    }

    @Nested
    @DisplayName("record 컴포넌트에 접근할 때,")
    class AccessComponents {

        @Test
        @DisplayName("직접 생성한 MyInfo의 컴포넌트에 접근할 수 있다.")
        void accessesRecordComponents_directly() {
            // given
            MyInfo info = new MyInfo(
                    "testuser1",
                    "홍길*",
                    "test@example.com",
                    "19990101",
                    1000);

            // when & then
            assertAll(
                    () -> assertThat(info.loginId()).isEqualTo("testuser1"),
                    () -> assertThat(info.name()).isEqualTo("홍길*"),
                    () -> assertThat(info.email()).isEqualTo("test@example.com"),
                    () -> assertThat(info.birthDate()).isEqualTo("19990101"),
                    () -> assertThat(info.pointBalance()).isEqualTo(1000));
        }
    }
}
