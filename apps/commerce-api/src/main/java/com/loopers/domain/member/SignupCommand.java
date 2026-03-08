package com.loopers.domain.member;

/*
    Command (어댑터 역할)
    : 비즈니스 로직을 수행하기 위한 입력 데이터로 도메인 서비스로 전달 되는 객체 ex) 고객이 입력한 값들을 담은 객체
    
    Q)왜 요청에 대한 MemberV1Dto 객체가 있는데 따로 Command 객체가 필요할까?
    A)의존성의 방향으로 도메인 레이어는 API레이어를 몰라야 한다. 
      MemberV1Dto를 사용하게 되면, API버전 변경시 도메인 코드도 수정해야 하고 테스트시 API용 DTO를 따로 만들어야 함.
*/
public record SignupCommand(
        String loginId,
        String password,
        String name,
        String email,
        String birthDate) {
}
