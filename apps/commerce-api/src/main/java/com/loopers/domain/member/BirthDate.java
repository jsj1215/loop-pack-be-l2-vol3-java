package com.loopers.domain.member;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Pattern;

/*
    VO(ValueObject)- 생년월일
    
    - 8자리 숫자
    - 날짜 유효성 검사

*/
public record BirthDate(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[0-9]{8}$");

    public BirthDate {
        String trimmed = value != null ? value.trim() : "";
        if (!PATTERN.matcher(trimmed).matches()) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                    "생년월일은 8자리 숫자로 입력해주세요. (예: 19990101)");
        }
        // 검증
        validateDate(trimmed);
        value = trimmed;
    }

    private static void validateDate(String dateStr) {
        try {
            int year = Integer.parseInt(dateStr.substring(0, 4));
            int month = Integer.parseInt(dateStr.substring(4, 6));
            int day = Integer.parseInt(dateStr.substring(6, 8));
            LocalDate date = LocalDate.of(year, month, day);

            if (date.isAfter(LocalDate.now())) {
                throw new CoreException(ErrorType.BAD_REQUEST,
                        "생년월일은 미래 날짜일 수 없습니다.");
            }
        } catch (DateTimeException e) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                    "유효하지 않은 날짜입니다.");
        }
    }
}
