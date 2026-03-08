package com.loopers.domain.member;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.regex.Pattern;

/*
    VO(ValueObject) - 로그인 아이디
    
    - 영문,숫자를 사용한 4~20자리 유효성 검사
    
*/
public record LoginId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[a-zA-Z0-9]{4,20}$");

    public LoginId {
        String trimmed = value != null ? value.trim() : "";
        if (!PATTERN.matcher(trimmed).matches()) { // 정규 표현식 PATTERN과 매치가 되지 않으면, 예외 발생
            throw new CoreException(ErrorType.BAD_REQUEST,
                    "로그인 아이디는 영문, 숫자만 사용하여 4~20자로 입력해주세요.");
        }
        value = trimmed; // 매치가 되면 Login Id를 리턴
    }
}
