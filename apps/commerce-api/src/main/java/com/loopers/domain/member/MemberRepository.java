package com.loopers.domain.member;

import java.util.Optional;

/*
    # Repository 인터페이스
    : 데이터베이스 접근 계층으로, 데이터 접근에 대한 추상화된 계약 정의
    
    - @Repository : 스프링 빈으로 등록
    여기서는 인터페이스만 정의 하고, 구현은 infrastructure 레이어에서 한다.
    의존성 역전 원칙(DIP)가 적용됨.
    도메인 레이어가 기술적 세부사항(DB, 프레임워크 등)을 알 필요가 없음.
*/

public interface MemberRepository {

    Member save(Member member);

    Optional<Member> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);

    boolean existsByEmail(String email);
}
