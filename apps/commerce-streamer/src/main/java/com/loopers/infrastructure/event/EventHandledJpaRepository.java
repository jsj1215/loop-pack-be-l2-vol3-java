package com.loopers.infrastructure.event;

import com.loopers.domain.event.EventHandled;
import org.springframework.data.jpa.repository.JpaRepository;

// 이벤트 처리 이력 JPA Repository (멱등성 보장용)
public interface EventHandledJpaRepository extends JpaRepository<EventHandled, String> {
}
