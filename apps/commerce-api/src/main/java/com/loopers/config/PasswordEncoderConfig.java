package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    @Bean // 스프링 실행시 컨테이너에 Bean(passwordEncoder)으로 등록.
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); // 실제 구현체 객체 생성되어 컨테이너에 저장.
    }

}
