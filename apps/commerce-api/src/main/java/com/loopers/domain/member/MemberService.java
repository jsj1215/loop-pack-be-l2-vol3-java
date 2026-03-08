package com.loopers.domain.member;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
/*
    Service
    : 비즈니스 로직을 처리하는 객체

    - @RequiredArgsConstructor : 생성자 주입
    - @Component : 스프링 빈으로 등록

*/
@RequiredArgsConstructor
@Component
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder; // 의존성 주입 요청

    /*
     * 회원가입
     */
    public Member signup(SignupCommand command) {// command는 서비스로 전달하기 위한 객체

        // 1. Value Object 생성 (유효성 검사 수행 + trim)
        // Value Object는 값 자체가 중요한 객체이며, 생성 시점에 유효성 검사를 하여 유효하지 않으면 객체 생성이 안됨.
        LoginId loginId = new LoginId(command.loginId());
        Password password = new Password(command.password());
        MemberName name = new MemberName(command.name());
        Email email = new Email(command.email());
        BirthDate birthDate = new BirthDate(command.birthDate());

        // 2. 비밀번호에 생년월일 포함 여부 검증
        password.validateNotContainsBirthDate(birthDate.value());

        // 3. 로그인 ID 중복 검사
        if (memberRepository.existsByLoginId(loginId.value())) {
            throw new CoreException(ErrorType.CONFLICT, "이미 사용 중인 로그인 아이디입니다.");
        }

        // 4. 이메일 중복 검사
        if (memberRepository.existsByEmail(email.value())) {
            throw new CoreException(ErrorType.CONFLICT, "이미 사용 중인 이메일입니다.");
        }

        // 5. 비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(password.value());

        // 6. Member 생성 및 저장
        Member member = new Member(loginId, encodedPassword, name, email, birthDate);
        return memberRepository.save(member);
    }

    /*
     * 인증(로그인)
     */
    public Member authenticate(String loginId, String rawPassword) {
        if (loginId == null || rawPassword == null) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "인증에 실패했습니다.");
        }

        Member member = memberRepository.findByLoginId(loginId)
                .orElseThrow(() -> new CoreException(ErrorType.UNAUTHORIZED, "인증에 실패했습니다."));

        if (!passwordEncoder.matches(rawPassword, member.getPassword())) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "인증에 실패했습니다.");
        }

        return member;
    }

    /*
     * 비밀번호 변경
     */
    public void changePassword(Member member, String currentPassword, String newPassword) {
        // 1. 기존 비밀번호 일치 검사
        if (!passwordEncoder.matches(currentPassword, member.getPassword())) {
            throw new CoreException(ErrorType.BAD_REQUEST, "기존 비밀번호가 일치하지 않습니다.");
        }

        // 2. 새 비밀번호가 현재 비밀번호와 동일한지 검사
        if (passwordEncoder.matches(newPassword, member.getPassword())) {
            throw new CoreException(ErrorType.BAD_REQUEST, "현재 비밀번호와 동일한 비밀번호는 사용할 수 없습니다.");
        }

        // 3. 새 비밀번호 유효성 검사
        Password password = new Password(newPassword);

        // 4. 새 비밀번호에 생년월일 포함 여부 검증
        password.validateNotContainsBirthDate(member.getBirthDate());

        // 5. 비밀번호 암호화 및 변경
        String encodedNewPassword = passwordEncoder.encode(password.value());
        member.changePassword(encodedNewPassword);

        // 6. 변경된 비밀번호 저장 (detached entity 처리)
        memberRepository.save(member);
    }
}
