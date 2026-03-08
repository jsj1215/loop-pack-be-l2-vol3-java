package com.loopers.domain.member;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.regex.Pattern;

/*
    VO(ValueObject) - 비밀번호
    
    - 영문 대소문자, 숫자, 특수문자만 사용하여 8~16자 유효성 검사
    - 생년월일 포함 여부 체크
    
*/
public record Password(String value) {

    private static final Pattern PATTERN = Pattern.compile(
            "^[a-zA-Z0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]{8,16}$");

    public Password {
        String trimmed = value != null ? value.trim() : "";
        if (!PATTERN.matcher(trimmed).matches()) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                    "비밀번호는 영문 대소문자, 숫자, 특수문자만 사용하여 8~16자로 입력해주세요.");
        }
        value = trimmed;
    }

    // 비밀번호에 생년월일 포함 여부 체크
    public void validateNotContainsBirthDate(String birthDate) {
        if (birthDate != null && value.contains(birthDate)) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                    "비밀번호에 생년월일을 포함할 수 없습니다.");
        }
    }
}
