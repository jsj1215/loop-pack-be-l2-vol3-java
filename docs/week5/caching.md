# 캐싱(Caching) — 인덱스 다음 단계, DB를 안 가는 전략

인덱스를 설계하면서 상품 목록 조회를 66ms → 0.2ms로 개선했다.
하지만 트래픽이 더 몰리면 어떻게 될까?
아무리 인덱스를 잘 걸어도, **매 요청마다 DB 쿼리를 실행한다는 사실** 자체가 병목이 될 수 있다.

이때 고려할 수 있는 것이 **캐싱(Caching)**이다.

---

## 1. 캐싱이란

### 1.1 핵심 개념

캐싱은 자주 요청되는 데이터를 **원본(DB)보다 빠른 저장소에 미리 복사해두고**,
요청이 오면 원본 대신 복사본을 돌려주는 기법이다.

```
[ 캐시 없을 때 ]
Client → API Server → DB (매번 쿼리 실행)
                        ↑ 느림 (디스크 I/O, 쿼리 파싱, 실행 계획 등)

[ 캐시 있을 때 ]
Client → API Server → Cache(Redis) → 데이터 있으면 바로 반환 (Cache Hit)
                          ↓ 데이터 없으면
                         DB → 조회 후 캐시에 저장 → 반환 (Cache Miss)
```

책에서 비유하면:
- **인덱스**는 책의 "목차"다 — 목차를 보면 원하는 내용을 빠르게 찾을 수 있다.
- **캐시**는 자주 찾는 페이지에 붙여놓은 "포스트잇"이다 — 목차조차 볼 필요 없이 바로 펼칠 수 있다.

인덱스가 **쿼리를 빠르게 실행**하는 것이라면,
캐시는 **쿼리 자체를 실행하지 않는 것**이다.

### 1.2 왜 빠른가 — 저장소별 속도 차이

| 저장소 | 읽기 지연(Latency) | 특징 |
|--------|-------------------|------|
| L1/L2 CPU 캐시 | ~1ns | CPU 내부 캐시, 가장 빠름 |
| 메모리(RAM) | ~100ns | Redis, Local Cache가 여기에 해당 |
| SSD (디스크) | ~100μs (~100,000ns) | MySQL 데이터 파일 |
| HDD (디스크) | ~10ms (~10,000,000ns) | 전통적 디스크 |

Redis는 데이터를 **메모리에 저장**하기 때문에 디스크 기반 DB보다 10~100배 빠르다.
네트워크 통신을 포함하더라도 보통 **0.1~0.5ms** 수준으로 응답한다.

```
MySQL 인덱스 조회:  ~0.2ms (인덱스 최적화 후)
Redis 캐시 조회:    ~0.1ms (네트워크 포함)
```

이 수치만 보면 "인덱스 걸면 0.2ms인데 캐시까지 필요한가?" 싶을 수 있다.
하지만 캐시의 진짜 가치는 **단건 응답 속도**가 아니라 **DB 부하 자체를 줄이는 것**에 있다.

### 1.3 캐시의 진짜 가치 — DB 부하 감소

```
[ 캐시 없을 때 — 초당 1,000 요청 ]
1,000 요청 → 1,000 DB 쿼리 → DB CPU/커넥션 풀 포화 → 응답 지연 → 장애

[ 캐시 있을 때 — 초당 1,000 요청, Hit Rate 95% ]
1,000 요청 → 950 캐시 응답 (0.1ms) + 50 DB 쿼리 → DB 여유 → 안정적 서비스
```

DB 커넥션은 유한한 자원이다. (기본 HikariCP 풀 사이즈 10개)
1,000명이 동시에 상품 목록을 조회하면 10개의 커넥션을 두고 경쟁하게 되고,
대기 시간이 쌓이면서 응답 시간이 급격히 늘어난다.

캐시는 DB에 도달하는 요청 자체를 줄여서 **커넥션 경합을 완화**하고,
DB가 진짜 필요한 작업(주문 생성, 재고 차감 등)에 집중할 수 있게 해준다.

---

## 2. 핵심 용어

### 2.1 Cache Hit / Cache Miss

| 용어 | 설명 | 결과 |
|------|------|------|
| **Cache Hit** | 캐시에 데이터가 있음 | 바로 반환 (빠름) |
| **Cache Miss** | 캐시에 데이터가 없음 | DB 조회 → 캐시 저장 → 반환 (느림) |
| **Hit Rate** | 전체 요청 중 Hit 비율 | 높을수록 캐시 효과 큼 |

```
Hit Rate = Cache Hit 수 / 전체 요청 수 × 100

예: 1,000 요청 중 950 Hit → Hit Rate 95%
    → DB 쿼리 50번만 실행 (95% 절감)
```

일반적으로 **Hit Rate 80% 이상**이면 캐시 도입 효과가 있다고 본다.
상품 목록처럼 동일 조건 조회가 반복되는 패턴에서는 90% 이상도 기대할 수 있다.

### 2.2 TTL (Time To Live)

캐시 데이터의 **만료 시간**이다. TTL이 지나면 캐시에서 자동으로 삭제되고,
다음 요청 시 DB에서 다시 조회하여 캐시를 갱신한다.

```
TTL = 60초로 설정했을 때:

T=0s   : Cache Miss → DB 조회 → 캐시 저장 (TTL 60초 시작)
T=10s  : Cache Hit → 캐시에서 반환
T=30s  : Cache Hit → 캐시에서 반환
T=60s  : TTL 만료 → 캐시 삭제
T=61s  : Cache Miss → DB 조회 → 캐시 재저장 (TTL 다시 시작)
```

TTL 설정은 **정합성과 성능의 트레이드오프**이다:

| TTL | 정합성 | 성능(Hit Rate) | 적합한 경우 |
|-----|--------|---------------|------------|
| 짧음 (10~30초) | 높음 (자주 갱신) | 낮음 (Miss 빈번) | 자주 변경되는 데이터 |
| 보통 (1~5분) | 보통 | 보통 | 상품 목록, 검색 결과 |
| 긺 (10분~1시간) | 낮음 (오래된 데이터 노출) | 높음 (Hit 많음) | 거의 안 바뀌는 데이터 |

### 2.3 Cache Eviction (캐시 제거)

TTL 만료 외에도 캐시를 제거해야 하는 상황이 있다.
데이터가 변경되었을 때 **능동적으로 캐시를 삭제**하는 행위를 Eviction이라 한다.

여기서 중요한 점은, **Eviction은 "삭제만" 하고 끝난다는 것**이다.
삭제 후 자동으로 최신 데이터를 다시 채워주는 프로세스가 포함되어 있지 않다.

```
Eviction 발생 (TTL 만료 or 수동 삭제)
  → 캐시에서 해당 키 제거
  → 끝. 캐시는 비어 있는 상태.
  → (시간 경과...)
  → 다음 요청이 들어옴 → Cache Miss → DB 조회 → 캐시에 새로 저장
```

즉, **"누군가 조회해야 비로소 갱신된다."**
이것이 Cache-Aside 패턴의 핵심 특성이며, 두 가지 실질적인 문제를 만든다:

1. **Cold Start** — Eviction 후 첫 번째 요청자는 항상 느린 응답(DB 조회)을 경험한다.
2. **Cache Stampede** — TTL 만료 직후 동시에 많은 요청이 몰리면, 모두 Cache Miss → DB 폭주가 발생한다. (5.3절에서 상세 설명)

#### Eviction 후 갱신까지 하려면?

Eviction 자체에는 갱신이 포함되어 있지 않으므로,
삭제와 동시에 최신 데이터로 채우고 싶다면 **별도의 갱신 전략**을 조합해야 한다.

| 방식 | 동작 | Cold Start | 구현 복잡도 |
|------|------|-----------|-----------|
| **Cache-Aside (기본)** | 삭제만. 다음 요청 시 갱신 | 있음 | 낮음 |
| **삭제 + 즉시 재조회** | 삭제 후 바로 DB 조회 → 캐시 저장 | 없음 | 낮음 (쓰기 로직에 조회 추가) |
| **사전 갱신 (Background Refresh)** | TTL 만료 전에 백그라운드에서 미리 DB 조회 → 캐시 교체 | 없음 | 높음 (별도 스레드/스케줄러 필요) |
| **Write-Through** | 데이터 변경 시 DB + 캐시 동시 갱신 | 없음 | 보통 |
| **스케줄러 갱신** | 주기적 배치로 캐시를 갱신 | 갱신 주기만큼 존재 | 보통 |

```java
// [Cache-Aside — 기본] 삭제만 하고 끝
public Product update(Long productId, ...) {
    Product product = productRepository.save(...);
    cacheManager.delete("product:search:*");  // 삭제만. 다음 조회 시 갱신됨.
    return product;
}

// [삭제 + 즉시 재조회] 삭제 후 바로 캐시를 다시 채움
public Product update(Long productId, ...) {
    Product product = productRepository.save(...);
    cacheManager.delete("product:search:*");  // 기존 캐시 삭제
    // 주요 조건의 캐시를 미리 워밍
    warmUpCache();                             // DB 조회 → 캐시 저장
    return product;
}
```

실무에서는 Cache-Aside를 기본으로 사용하면서,
트래픽이 높은 핵심 캐시에 대해서만 사전 갱신이나 워밍을 추가하는 방식이 일반적이다.

#### 메모리가 부족할 때의 Eviction 정책

캐시 저장소의 메모리가 가득 차면 어떤 데이터를 먼저 삭제할지 정해야 한다.
이때도 마찬가지로 **삭제만 하고 갱신은 하지 않는다.**
Redis에서는 `maxmemory-policy` 설정으로 삭제 대상 선정 기준을 제어한다.

| 정책 | 설명 | 적합한 경우 |
|------|------|------------|
| **LRU** (Least Recently Used) | 가장 오래전에 사용된 키 삭제 | 최근 인기 데이터 유지 (가장 일반적) |
| **LFU** (Least Frequently Used) | 사용 빈도가 가장 낮은 키 삭제 | 꾸준히 인기 있는 데이터 유지 |
| **TTL 기반** | 만료 시간이 가장 가까운 키 삭제 | TTL이 설정된 키만 대상으로 제거 |
| **noeviction** | 삭제하지 않고 에러 반환 | 데이터 유실 방지가 중요할 때 |

일반적으로 캐시 용도에서는 **allkeys-lru** 정책을 사용한다.

---

## 3. 캐시 저장소 종류

### 3.1 Local Cache vs Remote Cache

```
[ Local Cache ]
┌─────────────────────────────┐
│  API Server (JVM)           │
│  ┌───────────────────────┐  │
│  │  Local Cache (HashMap) │  │  ← 같은 프로세스 메모리
│  └───────────────────────┘  │
└─────────────────────────────┘

[ Remote Cache ]
┌──────────────┐         ┌──────────────┐
│  API Server  │ ──TCP──→│    Redis     │  ← 별도 프로세스/서버
└──────────────┘         └──────────────┘
```

| 구분 | Local Cache | Remote Cache (Redis) |
|------|-------------|---------------------|
| **속도** | 가장 빠름 (~1μs, 네트워크 없음) | 빠름 (~0.1ms, 네트워크 필요) |
| **용량** | JVM 힙 메모리에 제한 | 별도 서버 메모리 (수 GB~수십 GB) |
| **서버 간 공유** | 불가 (각 서버마다 별도 캐시) | 가능 (여러 서버가 같은 캐시 공유) |
| **정합성** | 서버마다 캐시 내용이 다를 수 있음 | 모든 서버가 같은 캐시 참조 |
| **장애 영향** | 서버 재시작 시 전부 소멸 | Redis 장애 시 전체 서비스 영향 |
| **대표 구현** | Caffeine, Guava, EhCache | Redis, Memcached |

### 3.2 다중 서버 환경에서의 Local Cache 문제

```
[ 서버 2대 구성 — Local Cache 사용 시 ]

User A → Server 1 → 상품 수정 → Server 1 캐시 갱신
User B → Server 2 → 상품 조회 → Server 2 캐시 (이전 데이터 반환!)
```

서버가 여러 대이면 Local Cache는 서버마다 다른 데이터를 보여줄 수 있다.
이를 해결하려면:
1. **Remote Cache(Redis)로 통일** — 가장 단순하고 일반적
2. **Local Cache + Pub/Sub 무효화** — 캐시 변경 시 다른 서버에 알림
3. **Local Cache + 짧은 TTL** — 약간의 불일치를 감수

상품 목록 조회처럼 여러 서버에서 동일한 결과를 보여줘야 하는 경우,
**Redis**가 가장 적합하다. 이 프로젝트에도 이미 Redis 인프라가 구성되어 있다.

---

## 4. 캐시 전략 패턴

### 4.1 Cache-Aside (Look-Aside) — 가장 일반적

애플리케이션이 캐시와 DB를 **직접 관리**하는 패턴이다.

```
[ 읽기 흐름 ]

    Client
      │
      ▼
  ┌──────────┐    1. 캐시 조회
  │ API 서버  │ ──────────────→ ┌───────┐
  │          │                  │ Redis │
  │          │ ←────────────── └───────┘
  │          │    2-a. Hit → 반환
  │          │
  │          │    2-b. Miss
  │          │ ──────────────→ ┌──────┐
  │          │                 │  DB  │
  │          │ ←────────────── └──────┘
  │          │    3. DB 결과를 캐시에 저장
  │          │ ──────────────→ ┌───────┐
  │          │                 │ Redis │
  └──────────┘                 └───────┘


[ 쓰기 흐름 ]

    Client
      │
      ▼
  ┌──────────┐    1. DB에 저장
  │ API 서버  │ ──────────────→ ┌──────┐
  │          │                  │  DB  │
  │          │ ←────────────── └──────┘
  │          │    2. 관련 캐시 삭제
  │          │ ──────────────→ ┌───────┐
  │          │                 │ Redis │
  └──────────┘                 └───────┘
```

```
읽기 의사코드:
  1. 캐시에서 key로 조회
  2. Hit → 캐시 데이터 반환
  3. Miss → DB 조회 → 결과를 캐시에 저장 (TTL 설정) → 반환

쓰기 의사코드:
  1. DB에 데이터 저장
  2. 관련 캐시 삭제 (다음 읽기 시 DB에서 최신 데이터 로드)
```

**장점:**
- 구현이 가장 단순하고 직관적
- 캐시 장애 시 DB로 fallback 가능 (캐시가 죽어도 서비스는 느려질 뿐 멈추지 않음)
- 실제로 조회되는 데이터만 캐싱 (불필요한 캐싱 방지)

**단점:**
- 최초 요청은 항상 Miss (Cold Start)
- 캐시 삭제와 DB 저장 사이에 짧은 불일치 구간 존재

**적합한 경우:** 읽기가 쓰기보다 훨씬 많은 경우 (상품 목록, 카테고리 등)

### 4.2 Write-Through

쓰기 시 **DB와 캐시를 동시에 갱신**하는 패턴이다.

```
[ 쓰기 흐름 ]

    Client
      │
      ▼
  ┌──────────┐    1. 캐시에 저장
  │ API 서버  │ ──────────────→ ┌───────┐
  │          │                 │ Redis │
  │          │                 └───────┘
  │          │    2. DB에 저장
  │          │ ──────────────→ ┌──────┐
  │          │                 │  DB  │
  └──────────┘                 └──────┘

※ 캐시와 DB 모두 성공해야 쓰기 완료
```

**장점:**
- 캐시와 DB가 항상 동기화 (정합성 높음)
- 읽기 시 Cache Miss가 거의 없음

**단점:**
- 모든 쓰기에 캐시 저장이 추가되어 쓰기 지연 증가
- 한 번도 읽히지 않는 데이터도 캐싱 (캐시 공간 낭비)

**적합한 경우:** 데이터 정합성이 매우 중요하고, 쓰기 후 즉시 읽기가 빈번한 경우

### 4.3 Write-Behind (Write-Back)

캐시에만 먼저 쓰고, **나중에 비동기로 DB에 반영**하는 패턴이다.

```
[ 쓰기 흐름 ]

    Client
      │
      ▼
  ┌──────────┐    1. 캐시에만 저장 (즉시 응답)
  │ API 서버  │ ──────────────→ ┌───────┐
  │          │                 │ Redis │
  └──────────┘                 └───────┘
                                   │
                                   │ 2. 비동기로 DB 반영
                                   │    (배치 / 일정 주기)
                                   ▼
                               ┌──────┐
                               │  DB  │
                               └──────┘
```

**장점:**
- 쓰기 성능 극대화 (캐시만 쓰고 즉시 반환)
- DB 부하 분산 (여러 쓰기를 모아서 배치 처리 가능)

**단점:**
- 캐시 장애 시 아직 DB에 반영되지 않은 데이터 유실 위험
- 구현 복잡도 높음 (비동기 처리, 장애 복구 로직 필요)

**적합한 경우:** 쓰기 빈도가 매우 높고, 일시적 데이터 유실을 감수할 수 있는 경우
(예: 좋아요 카운트, 조회수, 실시간 로그 등)

### 4.4 전략 비교

| 기준 | Cache-Aside | Write-Through | Write-Behind |
|------|------------|--------------|-------------|
| **읽기 성능** | Hit 시 빠름, Miss 시 DB 접근 | 항상 빠름 (캐시에 있음) | 항상 빠름 |
| **쓰기 성능** | 캐시 삭제만 하므로 빠름 | 느림 (캐시+DB 둘 다 쓰기) | 가장 빠름 (캐시만) |
| **정합성** | 짧은 불일치 구간 존재 | 높음 | 낮음 (비동기 지연) |
| **데이터 유실** | 없음 | 없음 | 가능 (캐시 장애 시) |
| **구현 복잡도** | 낮음 | 보통 | 높음 |
| **실무 사용** | 가장 일반적 | 정합성 요구 시 | 쓰기 극대화 시 |

---

## 5. 캐시 도입 시 고민해야 할 것들

### 5.1 캐시 키 설계

검색 조건 조합이 캐시 키가 된다.
키가 제대로 설계되지 않으면 의도치 않은 Hit/Miss가 발생한다.

```
패턴: {도메인}:{기능}:{조건들}

예시:
product:search:brandId=1:sort=LIKE_DESC:page=0:size=20
product:search:brandId=null:sort=LATEST:page=0:size=20
product:search:keyword=원피스:sort=PRICE_ASC:page=2:size=20
```

**주의할 점:**
- 조건이 많으면 키 조합이 폭발적으로 늘어남 → Hit Rate 하락
- 키에 `null`이나 빈 값이 들어갈 때의 처리 통일
- 키 네이밍에 일관된 규칙 사용 (정렬, 구분자 등)

### 5.2 캐시 무효화 — "컴퓨터 과학에서 가장 어려운 두 가지"

> "There are only two hard things in Computer Science: cache invalidation and naming things."
> — Phil Karlton

캐시 무효화가 어려운 이유는, 데이터가 변경되었을 때 **어떤 캐시를 삭제해야 하는지** 정확히 알기 어렵기 때문이다.

```
예: 상품 ID=100의 가격이 변경되었을 때

삭제해야 할 캐시 키는?

product:search:brandId=1:sort=LIKE_DESC:page=0:size=20    → 이 상품 포함?
product:search:brandId=1:sort=LATEST:page=0:size=20       → 이 상품 포함?
product:search:brandId=1:sort=PRICE_ASC:page=0:size=20    → 이 상품 포함?
product:search:brandId=null:sort=LIKE_DESC:page=0:size=20  → 이 상품 포함?
product:search:brandId=null:sort=LATEST:page=3:size=20     → 이 상품 포함?
... (수십~수백 개의 조합)
```

일반적인 해결 방법:

| 방법 | 설명 | 장점 | 단점 |
|------|------|------|------|
| **TTL에 의존** | 변경 시 아무것도 안 하고 만료를 기다림 | 구현 가장 단순 | TTL 동안 이전 데이터 노출 |
| **패턴 삭제** | `product:search:*`로 관련 키 일괄 삭제 | 구현 단순 | 무관한 캐시까지 삭제, `KEYS` 명령 성능 이슈 |
| **태그 기반** | 캐시에 태그를 붙이고 태그 단위로 삭제 | 정밀한 무효화 | 구현 복잡 |
| **이벤트 기반** | 데이터 변경 이벤트 발행 → 구독자가 캐시 삭제 | 도메인 간 결합도 낮음 | 인프라 복잡 (이벤트 시스템 필요) |

### 5.3 캐시 스탬피드 (Cache Stampede / Thundering Herd)

TTL이 만료된 직후, 동시에 수백~수천 요청이 **모두 Cache Miss**를 겪고
일제히 DB로 쿼리를 보내는 현상이다.

```
TTL 만료 직후:

요청 1 → Cache Miss → DB 조회
요청 2 → Cache Miss → DB 조회     ← 동시에 수백 요청이 같은 쿼리 실행
요청 3 → Cache Miss → DB 조회
...
요청 N → Cache Miss → DB 조회     ← DB 과부하 → 장애 가능
```

**대응 방법:**

**(1) 락 기반 갱신 (Mutex Lock)**
```
요청 1 → Cache Miss → 락 획득 → DB 조회 → 캐시 저장 → 락 해제
요청 2 → Cache Miss → 락 대기 → ... → 락 해제 후 캐시에서 조회
요청 3 → Cache Miss → 락 대기 → ... → 락 해제 후 캐시에서 조회
```
하나의 요청만 DB에 접근하고, 나머지는 캐시가 갱신될 때까지 대기한다.

**(2) TTL에 랜덤 jitter 추가**

jitter는 각 캐시 키의 TTL에 랜덤값을 더해서 **만료 시점을 제각각 다르게** 만드는 기법이다.
TTL을 모두 동일하게 설정하면 같은 시점에 생성된 캐시들이 동시에 만료되어 스탬피드가 발생하는데,
랜덤값을 더하면 만료 시점이 흩어지므로 Cache Miss도 분산된다.

```
[ jitter 없음 — TTL 60초 고정 ]

서버 시작 시점에 캐시 일괄 생성:
product:search:sort=LIKE_DESC:page=0   → TTL 60초 → T=60s에 만료
product:search:sort=LIKE_DESC:page=1   → TTL 60초 → T=60s에 만료
product:search:sort=LATEST:page=0      → TTL 60초 → T=60s에 만료
product:search:sort=PRICE_ASC:page=0   → TTL 60초 → T=60s에 만료

T=60s: 전부 동시 만료 → 모든 요청이 Cache Miss → DB에 쿼리 폭주
       ████████████████


[ jitter 있음 — TTL 60초 + random(0~10초) ]

product:search:sort=LIKE_DESC:page=0   → TTL 63초 → T=63s에 만료
product:search:sort=LIKE_DESC:page=1   → TTL 67초 → T=67s에 만료
product:search:sort=LATEST:page=0      → TTL 61초 → T=61s에 만료
product:search:sort=PRICE_ASC:page=0   → TTL 69초 → T=69s에 만료

→ 만료 시점이 흩어짐 → Cache Miss가 분산 → DB 부하 분산
       T=61s: ██
       T=63s: ██
       T=67s: ██
       T=69s: █
```

```java
// jitter 없음 — 모든 캐시가 정확히 60초 후 동시 만료
redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(60));

// jitter 있음 — 60~70초 사이에서 랜덤하게 만료
long jitter = ThreadLocalRandom.current().nextLong(0, 11); // 0~10초
redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(60 + jitter));
```

**(3) 사전 갱신 (Background Refresh)**
```
TTL = 60초, 갱신 임계값 = 50초

T=50초: 캐시 아직 유효하지만 백그라운드에서 미리 DB 조회 → 캐시 갱신
T=60초: 이미 갱신 완료 → Miss 발생하지 않음
```

### 5.4 데이터 정합성 — 캐시와 DB가 다를 수 있다

캐시를 사용하면 **필연적으로 정합성 문제**가 발생할 수 있다.

```
[ Race Condition 시나리오 ]

Thread A: 상품 가격 수정 (10,000원 → 15,000원)
  1. DB 업데이트: price = 15,000
  2. 캐시 삭제: product:search:... 삭제

Thread B: 상품 목록 조회 (Thread A의 1~2 사이에 실행)
  1. Cache Miss (아직 삭제 전 / 방금 삭제됨)
  2. DB 조회: price = 10,000원 (Thread A의 DB 업데이트 전)
  3. 캐시 저장: price = 10,000원    ← 이전 데이터가 캐시에 저장됨!
```

이런 레이스 컨디션을 완벽히 방지하려면 **분산 락** 등이 필요하지만,
대부분의 경우 TTL로 일정 시간 후 자연 치유되므로,
**"어느 정도의 불일치를 허용할 것인가"**를 서비스 특성에 맞게 판단하는 게 중요하다.

| 데이터 유형 | 허용 가능한 불일치 | 이유 |
|------------|-------------------|------|
| 상품 목록 순서 | 수 초~수 분 | 좋아요 수가 1~2 차이 나도 사용자 체감 없음 |
| 상품 가격 | 가능한 짧게 | 결제 시에는 반드시 DB에서 최신 값 사용 |
| 재고 수량 | 허용 불가 | 주문 시 반드시 실시간 DB 확인 필요 |
| 좋아요 수 | 수 초~수 분 | 정확한 수치보다 대략적 순위가 중요 |

---

## 6. 현재 프로젝트에 적용한다면

### 6.1 캐싱 적합성 분석

상품 목록 조회는 캐싱에 적합한 대표적인 케이스이다.

| 조건 | 현재 상황 | 캐싱 적합도 |
|------|----------|-----------|
| 읽기/쓰기 비율 | 읽기 >>> 쓰기 (목록 조회가 대부분) | 높음 |
| 데이터 변경 빈도 | 상품 정보는 자주 변경되지 않음 | 높음 |
| 동일 요청 반복 | 같은 정렬/필터 조건이 반복 | 높음 |
| 실시간성 요구 | 좋아요 수 정도 (약간의 지연 허용 가능) | 적당 |
| 인프라 준비 | Redis 이미 구성되어 있음 | 높음 |

### 6.2 전략 선택

| 결정 항목 | 선택 | 이유 |
|----------|------|------|
| **캐시 저장소** | Redis (Remote Cache) | 다중 서버 대응, 인프라 이미 있음 |
| **캐시 전략** | Cache-Aside | 구현 단순, 장애 시 DB fallback 가능 |
| **무효화 방식** | TTL + 변경 시 패턴 삭제 | 상품 변경 빈도가 낮아 TTL로 충분 |
| **TTL** | 1~5분 | 상품 목록은 실시간성 요구 낮음 |

### 6.3 캐싱 적합/부적합 기능 분류

| 기능 | 캐싱 적합 | 이유 |
|------|----------|------|
| 상품 목록 조회 (정렬/필터) | O | 읽기 빈도 높고, 변경 적고, 약간의 지연 허용 |
| 상품 상세 조회 | O | 동일 상품 반복 조회 |
| 브랜드 목록 조회 | O | 거의 안 바뀌고, 자주 조회 |
| 주문 생성 | X | 쓰기 작업, 실시간 재고 확인 필요 |
| 재고 차감 | X | 정합성 최우선, 캐시 사용 위험 |
| 쿠폰 사용 | X | 중복 사용 방지 필요, 실시간 상태 확인 필수 |

---

## 7. 인덱스와 캐시의 관계

인덱스와 캐시는 대체재가 아니라 **보완재**이다.

```
[ 최적화 계층 ]

1단계: 쿼리 최적화   — 불필요한 조인 제거, SELECT 최소화
2단계: 인덱스 설계   — 쿼리 실행 속도 자체를 개선
3단계: 캐싱 도입     — 쿼리 실행 횟수를 줄임
4단계: 검색 엔진 분리 — Elasticsearch 등으로 복잡한 검색을 DB에서 분리
```

인덱스 없이 캐시만 도입하면:
- Cache Miss 시 느린 쿼리가 그대로 실행됨
- 스탬피드 발생 시 DB에 느린 쿼리가 폭주 → 장애
- 캐시가 "느린 쿼리를 숨기는" 역할만 하게 됨

**인덱스로 쿼리 자체를 빠르게 만든 뒤, 캐시로 호출 횟수를 줄이는 것**이 올바른 순서이다.

```
현재 상태 (인덱스 적용 후):
  상품 목록 조회: ~0.2ms / 요청

캐시 적용 후 (Hit Rate 95% 가정):
  95% 요청: ~0.1ms (Redis)
  5% 요청:  ~0.2ms (DB, 인덱스 활용)
  DB 부하:  95% 감소
```

이 프로젝트에서는 인덱스 설계가 이미 완료되었으므로,
캐시를 도입하면 **DB 부하 감소 + 응답 속도 안정화**라는 추가 이점을 얻을 수 있다.

---

## 8. 로컬 캐시 — Caffeine

앞서 캐시 저장소를 Local Cache vs Remote Cache(Redis)로 나누어 비교했다.
Redis는 다중 서버 환경에서 캐시를 공유할 수 있다는 장점이 있지만,
**네트워크 통신 비용**이 발생하고 **Redis 서버 장애 시 전체 서비스에 영향**을 줄 수 있다.

반면 로컬 캐시는 같은 JVM 메모리에서 데이터를 읽기 때문에
네트워크 없이 **가장 빠른 응답 속도**를 제공한다.
Java 생태계에서 로컬 캐시의 사실상 표준(de facto standard)이 **Caffeine**이다.

### 8.1 Caffeine이란

Caffeine은 Java 8+ 기반의 **고성능 로컬 캐시 라이브러리**이다.
Google Guava Cache의 후속으로, Guava보다 **성능과 Hit Rate 모두 우수**하다.
Spring Boot에서도 로컬 캐시 구현체로 Caffeine을 공식 지원한다.

```
[ Redis (Remote Cache) ]
API Server → 네트워크 → Redis Server → 네트워크 → API Server
             ~0.1~0.5ms (네트워크 왕복 포함)

[ Caffeine (Local Cache) ]
API Server → JVM 힙 메모리에서 직접 읽기
             ~0.001ms (네트워크 없음, 수백~수천 나노초)
```

| 항목 | Caffeine | Redis |
|------|----------|-------|
| **읽기 속도** | ~1μs (나노초 단위) | ~0.1~0.5ms (네트워크 포함) |
| **저장 위치** | JVM 힙 메모리 | 별도 Redis 서버 메모리 |
| **직렬화** | 불필요 (Java 객체 그대로) | 필요 (JSON, byte[] 등으로 변환) |
| **서버 간 공유** | 불가 | 가능 |
| **서버 재시작 시** | 캐시 전부 소멸 | 유지 |

### 8.2 Caffeine 핵심 기능

#### (1) 만료 정책 (Expiration)

캐시 엔트리를 언제 만료시킬지 설정한다.

```java
Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(60))   // 쓰기 후 60초 뒤 만료
        .expireAfterAccess(Duration.ofSeconds(30))  // 마지막 접근 후 30초 뒤 만료
        .build();
```

| 설정 | 동작 | 적합한 경우 |
|------|------|------------|
| `expireAfterWrite` | **저장 시점**부터 TTL 카운트 | 데이터 신선도가 중요할 때 (상품 목록) |
| `expireAfterAccess` | **마지막 조회 시점**부터 TTL 카운트 | 자주 조회되는 데이터를 오래 유지하고 싶을 때 |

`expireAfterWrite`는 Redis의 TTL과 같은 개념이다.
`expireAfterAccess`는 **누군가 계속 읽으면 만료되지 않는다**는 점이 다르다.
둘 다 설정하면 **둘 중 먼저 도달하는 조건**에 의해 만료된다.

#### (2) 최대 크기 제한 (Maximum Size)

JVM 힙 메모리는 유한하므로 캐시 엔트리 수를 제한해야 한다.
초과 시 Caffeine이 자체적으로 Window TinyLFU 알고리즘 기반 eviction을 수행한다.

```java
Caffeine.newBuilder()
        .maximumSize(10_000)  // 최대 10,000개 엔트리
        .build();
```

Caffeine의 eviction 알고리즘인 **Window TinyLFU**는
최근성(Recency)과 빈도(Frequency)를 함께 고려하는 알고리즘으로,
단순 LRU나 LFU보다 **높은 Hit Rate**를 제공한다.

```
[ LRU — 최근에 안 쓴 것을 제거 ]
문제: 한 번만 대량 조회된 데이터가 자주 쓰이는 데이터를 밀어냄

[ LFU — 적게 쓴 것을 제거 ]
문제: 과거에 인기 있었지만 지금은 안 쓰이는 데이터가 계속 남음

[ Window TinyLFU — Caffeine ]
최근성 + 빈도를 함께 고려 → 두 문제를 모두 완화
```

#### (3) 사전 갱신 (Refresh After Write)

앞서 설명한 사전 갱신을 Caffeine은 **설정 한 줄**로 지원한다.
Redis에서는 직접 구현해야 했던 기능이 내장되어 있다.

```java
LoadingCache<String, List<Product>> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(60))     // TTL: 60초 후 만료
        .refreshAfterWrite(Duration.ofSeconds(50))     // 임계값: 50초 후 백그라운드 갱신
        .maximumSize(1_000)
        .build(key -> productService.searchFromDB(key)); // Cache Miss 또는 갱신 시 호출

// 사용
List<Product> products = cache.get(cacheKey);
// 50초 이전: 캐시에서 바로 반환
// 50~60초: 기존 값 반환 + 백그라운드에서 자동 갱신
// 60초 이후: 만료되어 동기 조회 (보통 여기까지 오기 전에 갱신 완료)
```

```
T=0s  : Cache Miss → DB 조회 → 캐시 저장
T=30s : Cache Hit → 즉시 반환
T=50s : 요청 들어옴 → 기존 값 반환 + 백그라운드에서 DB 조회 → 캐시 교체
T=51s : Cache Hit → 갱신된 최신 데이터 반환
T=100s: 다시 임계값 도달 → 백그라운드 갱신 반복
```

주의할 점은 `refreshAfterWrite`는 **요청이 들어와야 갱신이 트리거된다**는 것이다.
아무도 조회하지 않으면 갱신도 일어나지 않는다.
이는 "안 읽히는 데이터까지 갱신하느라 자원을 낭비하지 않는다"는 장점이기도 하다.

#### (4) 통계 (Statistics)

Hit Rate, Miss Rate, eviction 수 등을 모니터링할 수 있다.

```java
Cache<String, List<Product>> cache = Caffeine.newBuilder()
        .maximumSize(1_000)
        .recordStats()  // 통계 수집 활성화
        .build();

CacheStats stats = cache.stats();
stats.hitRate();       // 0.95 → 95% Hit Rate
stats.missRate();      // 0.05
stats.evictionCount(); // eviction 발생 횟수
```

### 8.3 Spring Boot에서 Caffeine 적용

#### (1) 의존성 추가

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")
}
```

#### (2) 캐시 설정

```java
@EnableCaching
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(3))  // TTL 3분
                .maximumSize(1_000)                        // 최대 1,000개
                .recordStats());                           // 통계 수집
        return cacheManager;
    }
}
```

#### (3) 서비스에서 사용 — 어노테이션 방식

```java
@Service
public class ProductService {

    // 조회 시 캐시 적용
    @Cacheable(value = "productSearch", key = "#condition.toString() + '_' + #pageable.toString()")
    public Page<Product> search(ProductSearchCondition condition, Pageable pageable) {
        return productRepository.search(condition, pageable);
    }

    // 수정 시 캐시 무효화
    @CacheEvict(value = "productSearch", allEntries = true)
    public Product update(Long productId, ...) {
        // DB 업데이트
    }
}
```

| 어노테이션 | 동작 |
|-----------|------|
| `@Cacheable` | 캐시에 있으면 반환, 없으면 메서드 실행 후 캐시 저장 |
| `@CacheEvict` | 해당 캐시 삭제 |
| `@CachePut` | 메서드를 항상 실행하고 결과를 캐시에 저장 (갱신) |

#### (4) 캐시별 다른 설정이 필요할 때

상품 목록과 브랜드 목록은 변경 빈도가 다르므로 TTL을 다르게 가져가고 싶을 수 있다.

```java
@EnableCaching
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        // 캐시별로 다른 설정 적용
        Map<String, Cache> cacheMap = Map.of(
                "productSearch", buildCache(Duration.ofMinutes(3), 1_000),
                "brandList", buildCache(Duration.ofMinutes(30), 100),
                "productDetail", buildCache(Duration.ofMinutes(5), 5_000)
        );

        // SimpleCacheManager로 개별 설정
        SimpleCacheManager simpleCacheManager = new SimpleCacheManager();
        simpleCacheManager.setCaches(cacheMap.entrySet().stream()
                .map(e -> new CaffeineCache(e.getKey(), e.getValue()))
                .toList());
        return simpleCacheManager;
    }

    private Cache buildCache(Duration ttl, long maxSize) {
        return Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .recordStats()
                .build();
    }
}
```

| 캐시 이름 | TTL | 최대 크기 | 이유 |
|----------|-----|----------|------|
| `productSearch` | 3분 | 1,000 | 좋아요 수 변동이 있으므로 비교적 짧게 |
| `brandList` | 30분 | 100 | 브랜드 정보는 거의 안 바뀜 |
| `productDetail` | 5분 | 5,000 | 개별 상품 조회가 많으므로 크기 크게 |

### 8.4 로컬 캐시(Caffeine)의 트레이드오프

#### 장점

| 항목 | 설명 |
|------|------|
| **극한의 속도** | 네트워크 없이 JVM 메모리 직접 접근, Redis 대비 100~500배 빠름 |
| **네트워크 장애 무관** | Redis 서버 다운, 네트워크 끊김 등의 영향을 받지 않음 |
| **직렬화 비용 없음** | Java 객체를 그대로 저장/반환, JSON 변환 불필요 |
| **사전 갱신 내장** | `refreshAfterWrite` 설정 한 줄로 백그라운드 갱신 가능 |
| **인프라 추가 불필요** | 별도 서버 없이 의존성 추가만으로 사용 가능 |

#### 단점

| 항목 | 설명 | 영향 |
|------|------|------|
| **서버 간 캐시 불일치** | 각 서버가 독립적인 캐시를 가짐 | Server A에서 수정한 데이터가 Server B 캐시에는 반영 안 됨 |
| **JVM 메모리 사용** | 캐시가 힙 메모리를 차지 | GC 부담 증가, OOM 위험 (maximumSize로 제어 필수) |
| **서버 재시작 시 소멸** | 프로세스 종료 = 캐시 전부 소멸 | 재시작 직후 모든 요청이 Cache Miss (Cold Start) |
| **캐시 무효화 어려움** | 다른 서버의 캐시를 삭제할 수 없음 | 즉시 무효화가 필요한 데이터에 부적합 |

#### 서버 간 불일치 문제 — 구체적 시나리오

```
[ 서버 2대 + Caffeine 로컬 캐시 ]

1. User A → Server 1 → 상품 가격 수정 (10,000원 → 15,000원)
   → Server 1 캐시 무효화 (정상)

2. User B → Server 2 → 상품 목록 조회
   → Server 2 캐시에는 아직 10,000원 (이전 데이터!)
   → TTL 만료될 때까지 이전 가격이 보임
```

이 문제를 완화하는 방법:

| 방법 | 구현 복잡도 | 정합성 |
|------|-----------|--------|
| **짧은 TTL** (1~3분) | 낮음 | TTL 동안은 불일치 허용 |
| **Pub/Sub 무효화** (Redis Pub/Sub로 다른 서버에 삭제 알림) | 보통 | 거의 즉시 반영 |
| **Redis + Caffeine 2계층 캐시** | 높음 | 높음 |

### 8.5 2계층 캐시 (L1: Caffeine + L2: Redis)

로컬 캐시의 속도와 Remote 캐시의 공유 장점을 **모두 취하는** 전략이다.

```
요청 → L1 (Caffeine, 로컬)  → Hit → 반환 (가장 빠름)
                              ↓ Miss
       L2 (Redis, 리모트)    → Hit → 반환 + L1에 저장
                              ↓ Miss
       DB                    → 조회 → L2 저장 + L1 저장 → 반환
```

```
[ 계층별 역할 ]

L1 (Caffeine):  TTL 짧게 (30초~1분), 크기 작게 (수백~수천 개)
                → 초고속 응답, 짧은 TTL로 불일치 최소화

L2 (Redis):     TTL 길게 (3~10분), 크기 크게
                → 서버 간 공유, L1 Miss 시 백업
                → DB 직접 접근 최소화

DB:             L1, L2 모두 Miss일 때만 접근
```

| 계층 | 속도 | 용량 | 공유 | TTL |
|------|------|------|------|-----|
| L1 (Caffeine) | ~1μs | 작음 (JVM 메모리) | 서버별 독립 | 짧게 (30초~1분) |
| L2 (Redis) | ~0.1ms | 큼 (별도 서버) | 전체 서버 공유 | 길게 (3~10분) |
| DB | ~0.2ms+ | 무제한 | - | - |

2계층 캐시는 **트래픽이 매우 높은 서비스**에서 효과적이지만,
캐시 무효화 로직이 두 계층 모두에 적용되어야 하므로 구현 복잡도가 높다.
현재 프로젝트 규모에서는 **단일 계층(Redis 또는 Caffeine 중 하나)**으로 시작하고,
트래픽 증가에 따라 2계층으로 확장하는 것이 적절하다.

### 8.6 로컬 캐시 vs Remote 캐시 — 어떤 상황에 무엇을 선택할까

| 기준 | Caffeine (로컬) 선택 | Redis (리모트) 선택 |
|------|---------------------|-------------------|
| **서버 대수** | 단일 서버 또는 불일치 허용 가능 | 다중 서버, 일관된 데이터 필요 |
| **응답 속도** | 극한의 속도가 필요 | 0.1ms 수준이면 충분 |
| **데이터 변경 빈도** | 낮음 (변경 시 불일치 영향 적음) | 높음 (즉시 무효화 필요) |
| **데이터 크기** | 작음 (JVM 메모리 내) | 큼 (별도 서버 메모리 활용) |
| **인프라** | 추가 인프라 없이 시작 | Redis 서버 필요 |
| **장애 격리** | 서버별 독립 (Redis 장애 무관) | Redis 장애 시 전체 영향 |

현재 프로젝트에서는 Redis 인프라가 이미 있고 다중 서버 배포를 고려하면 Redis가 기본 선택이지만,
**브랜드 목록처럼 거의 변하지 않고 크기가 작은 데이터**에는
Caffeine을 적용하여 Redis 호출마저 줄이는 것도 효과적인 전략이 될 수 있다.
