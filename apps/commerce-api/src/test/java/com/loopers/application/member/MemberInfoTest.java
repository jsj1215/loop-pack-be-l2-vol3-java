package com.loopers.application.member;

import com.loopers.domain.member.BirthDate;
import com.loopers.domain.member.Email;
import com.loopers.domain.member.LoginId;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [단위 테스트 - Application DTO]
 *
 * 테스트 대상: MemberInfo (Application Layer DTO)
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
 * - Member 엔티티에서 MemberInfo로의 변환 로직 검증
 */
@DisplayName("MemberInfo")
class MemberInfoTest {

    @Test
    @DisplayName("Member 엔티티로부터 생성하면 모든 필드가 올바르게 매핑된다.")
    void createsFromMember_withAllFieldsMapped() {
        // given
        Member mockMember = mock(Member.class);
        when(mockMember.getId()).thenReturn(1L);
        when(mockMember.getLoginId()).thenReturn("testuser1");
        when(mockMember.getName()).thenReturn("홍길동");
        when(mockMember.getEmail()).thenReturn("test@example.com");
        when(mockMember.getBirthDate()).thenReturn("19990101");

        // when
        MemberInfo info = MemberInfo.from(mockMember);

        // then
        assertAll(
                () -> assertThat(info.id()).isEqualTo(1L),
                () -> assertThat(info.loginId()).isEqualTo("testuser1"),
                () -> assertThat(info.name()).isEqualTo("홍길동"),
                () -> assertThat(info.email()).isEqualTo("test@example.com"),
                () -> assertThat(info.birthDate()).isEqualTo("19990101"));
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
        MemberInfo info = MemberInfo.from(member);

        // then
        assertAll(
                () -> assertThat(info.loginId()).isEqualTo("testuser1"),
                () -> assertThat(info.name()).isEqualTo("홍길동"),
                () -> assertThat(info.email()).isEqualTo("test@example.com"),
                () -> assertThat(info.birthDate()).isEqualTo("19990101"));
    }

    @Test
    @DisplayName("record의 컴포넌트에 직접 접근할 수 있다.")
    void accessesRecordComponents_directly() {
        // given
        MemberInfo info = new MemberInfo(
                1L,
                "testuser1",
                "홍길동",
                "test@example.com",
                "19990101");

        // when & then
        assertAll(
                () -> assertThat(info.id()).isEqualTo(1L),
                () -> assertThat(info.loginId()).isEqualTo("testuser1"),
                () -> assertThat(info.name()).isEqualTo("홍길동"),
                () -> assertThat(info.email()).isEqualTo("test@example.com"),
                () -> assertThat(info.birthDate()).isEqualTo("19990101"));
    }
}
