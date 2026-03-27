package com.loopers.interfaces.api.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 컨트롤러 메서드 파라미터에 인증된 회원 정보를 주입하기 위한 어노테이션.
// required=false로 설정 시 비인증 사용자도 허용한다.
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginMember {
    boolean required() default true;
}
