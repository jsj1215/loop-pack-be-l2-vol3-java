package com.loopers.domain.member;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.regex.Pattern;

/*
    VO(ValueObject) - 이메일
    
    - 이메일 형식 검사
    
*/
public record Email(String value) {

    private static final Pattern PATTERN = Pattern.compile(
            "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    public Email {
        String trimmed = value != null ? value.trim() : "";
        if (!PATTERN.matcher(trimmed).matches()) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                    "올바른 이메일 형식으로 입력해주세요.");
        }
        value = trimmed;
    }
}
