package com.loopers.domain.member;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.regex.Pattern;

/*
    VO(ValueObject) - 회원 이름
    
    - 한글 2~10자 유효성 검사
    - 마스킹 처리 메서드
*/
public record MemberName(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[가-힣]{2,10}$");

    public MemberName {
        String trimmed = value != null ? value.trim() : "";
        if (!PATTERN.matcher(trimmed).matches()) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                    "이름은 한글만 사용하여 2~10자로 입력해주세요.");
        }
        value = trimmed;
    }

    public String masked() {
        if (value.length() <= 1) {
            return "*";
        }
        return value.substring(0, value.length() - 1) + "*";
    }
}
