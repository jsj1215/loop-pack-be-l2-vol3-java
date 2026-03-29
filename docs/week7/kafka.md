# Apache Kafka 완전 정복 가이드

> 카프카의 기초 개념부터 실전 운영까지, 단계별로 정리한 학습 가이드

---

## 목차

1. [카프카란 무엇인가?](#1-카프카란-무엇인가)
2. [핵심 구조](#2-핵심-구조)
3. [Producer와 Consumer 심화](#3-producer와-consumer-심화)
4. [세부 설정값과 튜닝](#4-세부-설정값과-튜닝)
5. [실전 활용 패턴과 사례](#5-실전-활용-패턴과-사례)
6. [주의사항과 트러블슈팅](#6-주의사항과-트러블슈팅)
7. [실습 로드맵](#7-실습-로드맵)

---

## 1. 카프카란 무엇인가?

### 1.1 어원

Apache Kafka는 체코 출신 소설가 **프란츠 카프카**(Franz Kafka)에서 이름을 따왔다. LinkedIn 엔지니어 **제이 크렙스**(Jay Kreps)가 "글쓰기에 최적화된 시스템"이니까 작가 이름이 어울린다고 생각해서 지은 이름이다.

### 1.2 탄생 배경

2010년경 LinkedIn에는 회원 서비스, 검색 서비스, 추천 서비스, 광고 서비스, 모니터링 등 수많은 시스템이 있었다. 이 시스템들이 서로 직접 데이터를 주고받는 **스파게티 아키텍처** 문제가 있었다.

**문제점:**
- 시스템 N개 × N개 = 최대 N² 개의 개별 연결
- 각 연결마다 프로토콜, 데이터 형식, 에러 처리를 따로 구현
- 새 시스템 추가 시 기존 모든 시스템에 연결 필요

**해결:** 중앙에 메시지 허브(Kafka)를 두고, 모든 시스템은 Kafka 하나만 바라보는 구조로 변경.

### 1.3 한 줄 정의

> **Apache Kafka는 대용량의 실시간 데이터를 안정적으로 주고받을 수 있게 해주는 분산 메시지 스트리밍 플랫폼이다.**

### 1.4 핵심 특징 3가지

| 특징 | 설명 |
|------|------|
| **빠르다** | 초당 수백만 건의 메시지 처리 가능 |
| **안 잃어버린다** | 메시지를 디스크에 저장, 시스템 장애에도 데이터 보존 |
| **여러 명이 같은 데이터를 볼 수 있다** | 일정 기간 메시지를 보관하며 여러 Consumer가 각자의 속도로 읽기 가능 |

---

## 2. 핵심 구조

### 2.1 핵심 용어 요약

| 카프카 용어 | 비유 | 설명 |
|---|---|---|
| **Broker** | 우체국 지점 | 카프카 서버 1대. 여러 대를 묶어 클러스터 구성 |
| **Topic** | 우편함 카테고리 | 메시지의 주제별 분류 이름 (논리적 개념) |
| **Partition** | 우편함 안의 칸 | Topic을 나눈 물리적 단위, 병렬 처리의 핵심 |
| **Producer** | 편지 보내는 사람 | 메시지를 카프카에 발행하는 애플리케이션 |
| **Consumer** | 편지 받는 사람 | 메시지를 카프카에서 읽는 애플리케이션 |

### 2.2 Broker와 Cluster

- Broker는 카프카 서버 한 대를 의미
- 보통 3대 이상의 Broker를 묶어 **Cluster** 구성
- 한 대가 고장 나도 나머지가 일을 이어받아 **고가용성(High Availability)** 확보

### 2.3 Topic과 Partition

**Topic은 논리적 이름표이고, 실제 데이터가 저장되는 물리적 단위는 Partition이다.**

- 하나의 Topic은 여러 개의 Partition을 가질 수 있다
- 토픽마다 파티션 수가 다를 수 있다 (orders: 6개, user-logs: 3개, notifications: 1개)
- **다른 토픽끼리 같은 파티션을 공유하지 않는다** (완전히 독립)

**Partition의 핵심 특성:**
- 각 Partition 안에서 메시지는 **순서가 보장**됨 (append-only)
- Partition 사이의 순서는 **보장되지 않음**
- 파티션을 늘리면 병렬 처리가 가능해져 속도 향상

**Offset:**
- 각 Partition 내에서 메시지의 순번 (0, 1, 2, ...)
- Consumer가 "나 P0의 offset 2번까지 읽었어"라고 기록 → 다음엔 3번부터 읽음
- 일종의 북마크

**디스크 저장 구조:**
```
/kafka-logs/
├── orders-0/          ← orders 토픽의 P0
├── orders-1/          ← orders 토픽의 P1
├── user-logs-0/       ← user-logs 토픽의 P0
└── notifications-0/   ← notifications 토픽의 P0
```

### 2.4 물리적 관점 vs 논리적 관점

토픽과 브로커는 **포함 관계가 아니라 교차 관계**이다.

- **물리적 관점 (Broker가 가장 바깥):** Broker(서버) 안에 여러 토픽의 파티션들이 섞여서 저장
- **논리적 관점 (Topic이 가장 바깥):** Topic 안에 파티션이 있고, 그 파티션들이 여러 Broker에 흩어져 있음

### 2.5 Replication (복제)

**복제의 단위는 "파티션"이다.** 토픽 전체가 통째로 복제되는 게 아니라, 각 파티션이 개별적으로 여러 Broker에 복제된다.

**Leader와 Follower:**
- **Leader:** 해당 파티션에 대해 실제 읽기/쓰기를 처리하는 사본
- **Follower:** Leader의 데이터를 그대로 복사해두는 백업 사본
- Leader가 있는 Broker가 죽으면 Follower 중 하나가 자동으로 Leader로 승격

**핵심: 리더는 "브로커 단위"가 아니라 "파티션 단위"로 정해진다.**

예시 (replication.factor=3):
| | Broker 1 | Broker 2 | Broker 3 |
|---|---|---|---|
| P0 | **Leader** | Follower | Follower |
| P1 | Follower | **Leader** | Follower |
| P2 | Follower | Follower | **Leader** |

이렇게 Leader를 골고루 분산시키는 이유: 한 Broker에 부하가 몰리지 않게 하기 위함

### 2.6 Broker 간 동기화

**Pull 방식:** Leader가 Follower에게 밀어넣는 게 아니라, Follower가 Leader에게 "새 데이터 있으면 줘"하고 가져간다.

**동기화 과정:**
1. Producer → Leader에 메시지 전송
2. Leader가 디스크에 저장
3. Follower들이 Leader에게 "offset N 이후 줘" (Pull)
4. 동기화 완료 → **ISR(In-Sync Replicas)** 에 등록

**ISR (In-Sync Replicas):**
- Leader의 데이터를 잘 따라잡고 있는 복제본 목록
- 너무 뒤처지면 ISR에서 제외됨
- Leader가 죽었을 때 **ISR 안에 있는 Follower만** 새 Leader가 될 수 있음

### 2.7 Producer의 라우팅

**Producer는 브로커를 직접 지정하지 않는다.** 메타데이터를 통해 자동 라우팅된다.

1. Producer가 `bootstrap.servers`에 지정된 아무 Broker에 최초 연결
2. "토픽별로 어떤 Partition의 Leader가 어디에 있어?" 메타데이터 요청
3. 메타데이터를 캐싱해둠 (자동 갱신)
4. 이후 각 Partition의 Leader Broker에 직접 전송

### 2.8 Consumer Group

**같은 Group 안에서:** 하나의 Partition은 딱 하나의 Consumer만 담당
**다른 Group끼리:** 같은 데이터를 독립적으로 각각 읽을 수 있음

Consumer 수 > Partition 수이면 남는 Consumer는 놀게 됨!

---

## 3. Producer와 Consumer 심화

### 3.1 Producer 내부 파이프라인

```
메시지 → ① Serializer → ② Partitioner → ③ Record Buffer → ④ Sender → Broker
```

| 단계 | 역할 |
|------|------|
| **① Serializer** | 객체 → 바이트 배열 변환 |
| **② Partitioner** | 어떤 파티션으로 보낼지 결정 |
| **③ Record Buffer** | 파티션별 배치로 모아둠 |
| **④ Sender** | 별도 스레드가 배치를 Broker로 전송 |

### 3.2 파티션 결정 방식

- **Key가 없으면:** 라운드 로빈 (돌아가면서 고르게 분배)
- **Key가 있으면:** `partition = hash(key) % 파티션 수` → 같은 Key는 항상 같은 Partition
- **직접 지정:** 개발자가 파티션 번호를 명시

### 3.3 Message Key와 순서 보장

**문제:** Key 없이 보내면 같은 고객의 이벤트가 다른 파티션에 흩어져 순서가 꼬일 수 있음
- 예: "주문 생성" → P0, "결제 완료" → P1, "배송 시작" → P2
- Consumer 3이 가장 빨리 처리하면 "배송 시작"이 "주문 생성"보다 먼저 처리될 수 있음

**해결:** Key="고객A"로 보내면 고객A의 모든 이벤트는 항상 같은 Partition → 순서 보장

### 3.4 acks 설정 (Producer)

| 설정 | 동작 | 속도 | 안전 | 사용 사례 |
|------|------|------|------|-----------|
| `acks=0` | 보내고 확인 안 함 | 가장 빠름 | 가장 위험 (유실 가능) | 로그, 메트릭 |
| `acks=1` | Leader 저장 확인 | 빠름 | 중간 (Leader 죽으면 유실) | 일반적인 경우 |
| `acks=all` | Leader + 모든 ISR 확인 | 가장 느림 | 가장 안전 | 결제, 주문 등 |

### 3.5 배치 전송

메시지는 바로 전송되지 않고 배치로 모았다가 한꺼번에 보냄 → 성능 향상

- `batch.size` (기본 16KB): 한 배치의 최대 크기. 차면 즉시 전송
- `linger.ms` (기본 0ms): 배치 대기 시간. 0이면 즉시 전송
- 두 조건 중 **먼저 만족하는 쪽**이 전송 트리거

### 3.6 Consumer 동작 루프

```
poll() → 메시지 처리 → offset commit → poll() → ... (무한 반복)
```

**Pull 방식:** Consumer가 Broker에게 "내가 마지막으로 읽은 이후 데이터 줘" → Broker가 데이터 묶어서 반환

### 3.7 Offset Commit

- **auto commit** (`enable.auto.commit=true`): 주기적으로 자동 저장. 편하지만 유실 위험
- **manual commit** (`enable.auto.commit=false`): 처리 완료 후 직접 저장. 안전하지만 코드 필요

`auto.offset.reset`:
- `latest` (기본값): 지금부터 새 메시지만 읽음
- `earliest`: 처음부터 전부 읽음

### 3.8 Rebalancing

Consumer가 추가/제거/죽었을 때 파티션을 재분배하는 과정

**핵심:** 리밸런싱 동안 **모든 Consumer가 일시적으로 멈춤** (Eager 전략)

### 3.9 중복 발행 방지

네트워크 장애 등으로 같은 메시지가 2번 발행되는 경우 → **Idempotent Producer** 로 해결

`enable.idempotence=true` (카프카 3.0+ 기본값): Producer가 보내는 각 메시지에 고유 번호(Producer ID + Sequence Number)가 붙어 Broker가 자동으로 중복을 걸러줌

---

## 4. 세부 설정값과 튜닝

### 4.1 Broker 핵심 설정

| 설정 | 기본값 | 설명 | 권장 |
|------|--------|------|------|
| `broker.id` | 없음 (필수) | 각 Broker의 고유 번호 | 클러스터 내 유일해야 함 |
| `log.dirs` | /tmp/kafka-logs | 파티션 데이터 저장 경로 | 운영에서 절대 /tmp 사용 금지 |
| `num.partitions` | 1 | 토픽 생성 시 기본 파티션 수 | 3~12 |
| `default.replication.factor` | 1 | 기본 복제 수 | **운영에서 반드시 3** |
| `min.insync.replicas` | 1 | acks=all 시 최소 동기화 복제본 수 | **2 (황금 조합)** |
| `unclean.leader.election.enable` | false | ISR 밖 Follower의 Leader 승격 허용 | **false 유지** |

### 4.2 데이터 안전의 황금 조합

```
replication.factor=3 + min.insync.replicas=2 + acks=all
```

| 상황 | 결과 |
|------|------|
| Broker 1대 죽음 | 정상 작동 (2개 남아서 OK) |
| Broker 2대 죽음 | 쓰기 거부, 읽기는 가능 |

→ "서비스 잠깐 멈추더라도 데이터는 절대 잃지 않겠다"는 전략

### 4.3 Topic 핵심 설정

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `retention.ms` | 604800000 (7일) | 메시지 보관 기간. -1이면 영구 보관 |
| `retention.bytes` | -1 (무제한) | 파티션당 최대 저장 용량 |
| `cleanup.policy` | delete | delete=오래된 것 삭제, compact=같은 Key의 최신 값만 유지 |
| `segment.bytes` | 1073741824 (1GB) | 세그먼트 파일 크기 |
| `max.message.bytes` | 1048576 (1MB) | 한 메시지의 최대 크기 |

**Log Compaction (`cleanup.policy=compact`):**
같은 Key의 이전 값을 삭제하고 최신 값만 남김. 사용자 설정, 장바구니 상태 등 "최종 상태"만 중요한 경우에 유용.

### 4.4 Producer 핵심 설정

**배치 & 성능:**

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `batch.size` | 16384 (16KB) | 파티션별 배치 최대 크기 |
| `linger.ms` | 0 | 배치 대기 시간 |
| `buffer.memory` | 33554432 (32MB) | 전체 전송 버퍼 크기 |
| `compression.type` | none | 압축 방식 (none/gzip/snappy/lz4/zstd) |

**안전 & 신뢰성:**

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `acks` | all (3.0+) | 응답 확인 수준 |
| `enable.idempotence` | true (3.0+) | 중복 발행 방지 |
| `retries` | 2147483647 | 재시도 횟수 (사실상 무한) |
| `delivery.timeout.ms` | 120000 (2분) | 전체 전송 타임아웃 |

### 4.5 Consumer 핵심 설정

**그룹 & offset:**

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `group.id` | 없음 (필수) | Consumer Group 이름 |
| `auto.offset.reset` | latest | 처음 시작 시 읽기 위치 |
| `enable.auto.commit` | true | 자동 offset commit 여부 |

**헬스체크 & 리밸런싱:**

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `session.timeout.ms` | 45000 (45초) | heartbeat 미수신 시 "죽음" 판단 시간 |
| `heartbeat.interval.ms` | 3000 (3초) | "나 살아있어" 신호 주기 |
| `max.poll.interval.ms` | 300000 (5분) | poll() 간격 초과 시 리밸런싱 |
| `max.poll.records` | 500 | 한 번의 poll()로 가져오는 최대 메시지 수 |

**네트워크:**

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `fetch.min.bytes` | 1 | 최소 응답 데이터 크기 |
| `fetch.max.wait.ms` | 500 | 최대 대기 시간 |

### 4.6 실무 튜닝 시나리오

**"처리량을 최대로 올리고 싶어"**
- Producer: `batch.size=131072`, `linger.ms=20`, `compression.type=lz4`
- Consumer: `fetch.min.bytes=65536`, `max.poll.records=1000`
- Topic: 파티션 수 늘리기

**"지연시간을 최소로 줄이고 싶어"**
- Producer: `linger.ms=0`, `acks=1`
- Consumer: `fetch.min.bytes=1`, `fetch.max.wait.ms=100`

**"절대 데이터를 잃으면 안 돼"**
- Producer: `acks=all`, `enable.idempotence=true`
- Broker: `min.insync.replicas=2`, `replication.factor=3`
- Consumer: `enable.auto.commit=false` (수동 commit)

**"리밸런싱이 너무 자주 일어나"**
- Consumer: `session.timeout.ms=45000`, `max.poll.interval.ms=600000`, `max.poll.records=100`
- Static Group Membership 적용 (`group.instance.id`)

**"디스크가 부족해"**
- Topic: `retention.ms=86400000` (1일), `retention.bytes=1073741824` (1GB/파티션)
- `compression.type=zstd`, `cleanup.policy=compact` (해당되는 경우)

---

## 5. 실전 활용 패턴과 사례

### 5.1 이벤트 드리븐 아키텍처 (Event-Driven Architecture)

가장 대표적인 사용 사례. 마이크로서비스 간 직접 API 호출 대신, 이벤트를 카프카에 발행하고 필요한 서비스가 각자 구독.

**기존 방식 (강한 결합):**
- 주문 서비스 → 결제 서비스, 재고 서비스, 알림 서비스, 포인트 서비스 직접 호출
- 결제 서비스가 느리면 주문 서비스도 같이 느려짐
- 포인트 서비스가 죽으면 주문 자체가 실패

**이벤트 드리븐 (느슨한 결합):**
- 주문 서비스 → Kafka "주문 생성됨" 이벤트 발행 → 각 서비스가 독립 Consumer Group으로 구독
- 포인트 서비스가 죽어도 주문은 정상 처리, 나중에 복구 후 이어서 읽음
- 새 서비스 추가 시 Consumer Group만 추가하면 됨

### 5.2 CQRS (Command Query Responsibility Segregation)

쓰기(Command)와 읽기(Query)를 분리하는 패턴.

```
쓰기 → MySQL → CDC(Debezium) → Kafka → Consumer → Elasticsearch → 읽기
```

- 쓰기는 정규화된 RDB에
- 읽기는 검색에 최적화된 Elasticsearch/Redis에
- CDC(Change Data Capture): DB 변경을 자동으로 카프카에 전달

### 5.3 실시간 데이터 파이프라인

다양한 소스(웹 서버 로그, 앱 이벤트, DB 변경, IoT 센서)에서 데이터를 모아 분석 시스템(Elasticsearch, S3, Spark, Grafana)으로 전달.

**주요 도구:**
- **Kafka Connect:** 코드 없이 설정만으로 Source/Sink 연동 자동화
- **Kafka Streams:** 실시간 스트림 처리 라이브러리
- **Schema Registry:** 메시지 스키마(Avro/Protobuf) 중앙 관리
- **ksqlDB:** SQL로 스트림 처리

### 5.4 Saga 패턴 (분산 트랜잭션)

마이크로서비스에서 여러 서비스에 걸친 트랜잭션을 이벤트 기반으로 조율.

- **성공 흐름:** 주문 생성 → 결제 처리 → 재고 차감 → 배송 요청 (각 단계가 성공 이벤트 발행)
- **실패 시 보상 트랜잭션:** 재고 차감 실패 → 결제 취소(환불) → 주문 취소 이벤트 발행

### 5.5 실시간 모니터링/알림

이벤트 스트림을 Kafka Streams로 실시간 집계하여 이상 패턴 탐지.

예시:
- 5분 내 같은 IP에서 결제 실패 10회 이상 → 사기 의심 알림
- 1분 내 같은 계정 로그인 시도 20회 → 계정 자동 잠금
- API 응답 시간 p99가 2초 초과 → 장애 알림

### 5.6 카프카 사용 판단 기준

**카프카가 적합한 경우:**
- 대용량 실시간 스트리밍 (초당 수십만~수백만 건)
- 여러 소비자가 같은 데이터 필요
- 시스템 간 디커플링
- 이벤트 재처리 필요 (offset 리셋)

**카프카가 과한 경우:**
- 단순한 작업 큐 → RabbitMQ, SQS
- 요청-응답 패턴 → REST API, gRPC
- 소규모 트래픽 (하루 수천 건) → Redis Pub/Sub
- 강력한 ACID 트랜잭션 → RDB

**카프카 vs 대안 기술:**

| 비교 대상 | 카프카 장점 | 대안 장점 |
|---|---|---|
| RabbitMQ | 대용량, 보관형, 재처리 가능 | 스마트 라우팅, 간편 |
| Redis Pub/Sub | 보관, 내구성, 재처리 | 초경량, 낮은 지연 |
| AWS SQS/SNS | 높은 처리량, 유연한 소비 모델 | 완전 관리형, 간편 |
| Pulsar | 더 큰 생태계, 성숙한 도구 | 멀티 테넌시, 계층 저장 |

---

## 6. 주의사항과 트러블슈팅

### 6.1 Consumer Lag

**Consumer Lag = 최신 offset - Consumer가 읽은 offset**

카프카 운영에서 가장 중요한 모니터링 지표. Lag이 계속 증가하면 Consumer가 처리를 못 따라가고 있다는 신호.

**원인과 해결:**

| 원인 | 해결 |
|------|------|
| Consumer 처리가 느림 | 처리 로직 최적화, max.poll.records 줄이기 |
| Consumer 수 부족 | Consumer 인스턴스 추가 + 파티션 수 함께 늘리기 |
| 잦은 리밸런싱 | session.timeout.ms 늘리기, Static Membership |
| Producer 폭발적 증가 | 파티션 + Consumer 동시 스케일아웃 |

**모니터링 도구:** Kafka Lag Exporter + Grafana, Burrow, kafka-consumer-groups.sh

### 6.2 메시지 전달 보장 수준 (Delivery Semantics)

| 수준 | 설명 | 사용 사례 |
|------|------|-----------|
| **At-most-once** | 유실 가능, 중복 없음 | 로그, 메트릭 |
| **At-least-once** | 유실 없음, 중복 가능 | 대부분의 사용 사례 (Consumer에서 멱등성 처리 필요) |
| **Exactly-once** | 유실/중복 모두 없음 | `enable.idempotence=true` + `transactional.id` |

**실무에서 중복 방지하는 법:**
1. **DB 유니크 키:** 메시지 고유 ID로 중복 INSERT 거부
2. **멱등성 처리:** "잔액을 1000원으로 설정" (O) vs "잔액에 1000원 추가" (X)
3. **처리 기록 테이블:** 처리한 메시지 ID를 별도 기록, 이미 처리한 ID면 스킵

### 6.3 파티션 관련 주의사항

- **파티션은 늘릴 수 있지만 줄일 수 없다** → 처음에 보수적으로 시작 (3~12개)
- **파티션 늘리면 Key 기반 순서가 깨진다** → hash(key) % 파티션 수가 바뀌므로
- **Consumer 수 > 파티션 수** → 남는 Consumer는 놀게 됨
- **핫 파티션 문제** → 특정 Key에 트래픽 몰림 → Key 설계 세분화
- **파티션이 너무 많으면** → 메모리 증가, 리더 선출/리밸런싱 시간 증가, 파일 핸들 증가
- 권장: Broker당 4,000개 이하, 클러스터 전체 200,000개 이하

### 6.4 운영 체크리스트

**모니터링 필수 항목:**
- Consumer Lag (가장 중요)
- Broker 디스크 사용량 (80% 넘으면 대응)
- Under-Replicated Partitions (0이 아니면 복제 문제)
- ISR Shrink/Expand
- Request 처리 시간 (Produce/Fetch 지연)
- JVM GC 시간

**보안:**
- SSL/TLS 암호화 (브로커↔클라이언트)
- SASL 인증 (SCRAM, Kerberos)
- ACL 권한 관리 (토픽별 읽기/쓰기 분리)
- 네트워크 격리 (private 네트워크 배치)

**장애 대비:**
- Broker 최소 3대
- Rack awareness (다른 랙/AZ에 분산 배치)
- `unclean.leader.election.enable=false` 유지
- 정기적인 장애 훈련
- MirrorMaker 2 (다른 데이터센터 복제)

**흔한 실수 TOP 5:**
1. `replication.factor=1`로 운영 → 데이터 영구 손실 위험
2. auto.commit + 느린 처리 → 처리 안 된 메시지 유실
3. 큰 메시지를 카프카로 직접 전송 → S3에 저장하고 URL만 보내기
4. Consumer에서 예외 처리 없이 crash → 무한 재처리
5. 토픽/파티션 이름 규칙 없이 생성 → 네이밍 컨벤션 필수

### 6.5 트러블슈팅 가이드

**"Producer가 메시지를 못 보내요"**
1. bootstrap.servers 주소 확인
2. Broker 생존 확인 (`kafka-broker-api-versions.sh`)
3. 토픽 존재 여부 확인 (`kafka-topics.sh --list`)
4. ACL 권한 확인
5. buffer.memory 초과 여부 (BufferExhaustedException)
6. max.message.bytes 초과 여부

**"Consumer가 메시지를 못 읽어요"**
1. group.id 확인
2. 토픽 구독(subscribe) 여부 확인
3. auto.offset.reset 확인 (latest면 이전 메시지 안 읽음)
4. Consumer Group 상태 확인 (`kafka-consumer-groups.sh --describe`)
5. 리밸런싱 중인지 확인
6. max.poll.interval.ms 초과 여부

**"리밸런싱이 계속 일어나요"**
1. Consumer 로그에서 원인 확인 (heartbeat vs poll timeout)
2. Heartbeat 문제 → session.timeout.ms / heartbeat.interval.ms 늘리기
3. Poll 문제 → max.poll.interval.ms 늘리기 + max.poll.records 줄이기
4. GC 문제 → JVM heap 크기 조정
5. Static Group Membership 적용 (`group.instance.id`)
6. CooperativeStickyAssignor 사용

**"Broker 디스크가 꽉 찼어요"**
1. 용량 많이 차지하는 토픽 확인 (`du -sh /kafka-logs/*`)
2. retention.ms 줄이기
3. retention.bytes 설정
4. 불필요한 토픽 삭제
5. compression.type 설정
6. 디스크 증설 또는 Tiered Storage 검토

**"특정 파티션만 느려요"**
1. 핫 파티션 여부 확인 (특정 Key에 트래픽 몰림)
2. Leader Broker 과부하 확인
3. Leader 재분배 (`kafka-leader-election.sh`)
4. Key 설계 변경
5. Broker 디스크 I/O, 네트워크 확인

---

## 7. 실습 로드맵

### 7.1 로컬 환경 세팅

**Docker Compose (KRaft 모드, ZooKeeper 불필요):**

```yaml
version: '3'
services:
  kafka:
    image: apache/kafka:3.7.0
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LOG_DIRS: /tmp/kraft-logs
```

### 7.2 단계별 실습

#### Level 1 — 기본 동작 확인 (CLI)

**실습 1-1: 토픽 생성 & 메시지 주고받기**
```bash
# 토픽 생성
kafka-topics.sh --create --topic my-first-topic --partitions 3 --replication-factor 1

# Producer 실행
kafka-console-producer.sh --topic my-first-topic

# Consumer 실행 (다른 터미널)
kafka-console-consumer.sh --topic my-first-topic --from-beginning
```

**실습 1-2: 파티션과 순서 확인**
```bash
# Key 있는 메시지 전송
kafka-console-producer.sh --topic my-first-topic \
  --property parse.key=true --property key.separator=:

# 파티션별로 읽기
kafka-console-consumer.sh --topic my-first-topic --partition 0
```

**실습 1-3: Consumer Group 체험**
```bash
# 같은 group.id로 Consumer 3개 실행 → 메시지 분산 확인
kafka-console-consumer.sh --topic my-first-topic --group my-group

# Consumer Group 상태 확인
kafka-consumer-groups.sh --describe --group my-group
```

#### Level 2 — 코드로 Producer/Consumer 만들기

- Producer 코드 작성 (메시지 1000개 전송)
- acks 설정별 전송 속도 비교
- auto commit vs manual commit 중복/유실 차이 확인
- Consumer Lag 관찰 (일부러 Consumer를 느리게 만들기)

#### Level 3 — 장애 시뮬레이션 (심화)

**카테고리 A: Broker 장애 & 복구**
- A-1: Leader Broker 1대 죽이기 → Leader 선출 과정 관찰
- A-2: Broker 2대 동시 죽이기 → min.insync.replicas 효과 확인
- A-3: 롤링 재시작 → 서비스 중단 없이 전체 재시작 가능한지 확인
- A-4: 디스크 풀 시뮬레이션 → retention 변경으로 긴급 복구

**카테고리 B: 데이터 유실 & 복제 안전성**
- B-1: acks 설정별 유실 증명 (순번 메시지로 유실 수 정확히 카운트)
- B-2: Unclean Leader Election 실험 (데이터 유실 vs 서비스 가용성 트레이드오프)
- B-3: ISR 축소/확장 모니터링 (replica.lag.time.max.ms 민감도 비교)

**카테고리 C: Consumer 장애 & 리밸런싱**
- C-1: 리밸런싱 비용 정밀 측정 (밀리초 단위 gap 측정)
- C-2: Eager vs CooperativeSticky 전략 비교 (처리량 그래프 비교)
- C-3: Auto Commit의 함정 — 유실과 중복 재현

**카테고리 D: Producer 장애 & 극한 상황**
- D-1: 네트워크 지연 시뮬레이션 (tc로 지연 주입, timeout/재시도 관찰)
- D-2: 버퍼 초과 실험 (BackPressure, BufferExhaustedException)
- D-3: 멱등성 Producer 검증 (idempotence=true/false 중복 비교)

**카테고리 E: 성능 한계 & 병목 탐색**
- E-1: 처리량 한계 벤치마크 (kafka-producer-perf-test.sh로 변수별 측정)
- E-2: End-to-End 지연시간 측정 (p50, p99, p999)

#### Level 4 — 실전 시나리오

- 주문 시스템 이벤트 드리븐 구현
- Kafka Connect + CDC 파이프라인 (MySQL → Kafka → Elasticsearch)
- 모니터링 대시보드 구축 (JMX Exporter + Prometheus + Grafana)

### 7.3 유용한 도구

| 도구 | 용도 |
|------|------|
| **Kafka UI** (provectuslabs/kafka-ui) | 웹에서 토픽/메시지/Consumer Group 시각적 확인 |
| **kcat** (구 kafkacat) | CLI에서 빠르게 메시지 보내고 읽기 |
| **Offset Explorer** (구 Kafka Tool) | GUI로 토픽/파티션 탐색 |
| **Conduktor** | 카프카 관리 올인원 도구 |

### 7.4 테스트 결과 기록 템플릿

```markdown
## 시나리오: [시나리오 번호와 이름]

### 환경
- Broker: N대, 모드
- Topic: 이름, 파티션 수, replication.factor
- 메시지: 건수, 크기

### 결과
| 설정 | 전송 수 | 수신 수 | 유실 수 | 유실률 |
|------|---------|---------|---------|--------|
| ... | ... | ... | ... | ... |

### 관찰
- ...

### 결론
- ...
```

---

## 참고

- Apache Kafka 공식 문서: https://kafka.apache.org/documentation/
- Confluent 문서: https://docs.confluent.io/
- Kafka: The Definitive Guide (O'Reilly)