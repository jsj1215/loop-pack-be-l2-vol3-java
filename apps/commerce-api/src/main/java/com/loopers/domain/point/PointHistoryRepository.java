package com.loopers.domain.point;

/*
    # Repository 인터페이스
    : 데이터베이스 접근 계층으로, 데이터 접근에 대한 추상화된 계약 정의

    여기서는 인터페이스만 정의 하고, 구현은 infrastructure 레이어에서 한다.
    의존성 역전 원칙(DIP)가 적용됨.
*/
public interface PointHistoryRepository {

    PointHistory save(PointHistory pointHistory);
}
