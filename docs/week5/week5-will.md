# Week 5 — WIL (What I Learned)

## 이번 주 과제 목표

1. **상품 목록 조회 성능 개선** — 10만건 데이터 기반 인덱스 최적화
2. **좋아요 수 정렬 구조 개선** — 비정규화 vs Materialized View 비교 후 선택
3. **캐시 적용** — 로컬 캐시(Caffeine) + Redis 캐시 비교 및 적용

---

## 1. 인덱스 최적화 — "어디에 걸까"가 아니라 "왜 이 인덱스여야 하는가"

### 무엇을 했는가

상품 데이터 10만건을 준비하고, `EXPLAIN / EXPLAIN ANALYZE`로 실행 계획을 분석하며 인덱스를 단계별로 설계했다.

### 핵심 성과

| 쿼리 | 인덱스 없음 | 인덱스 적용 후 | 개선 |
|------|-----------|-------------|------|
| 브랜드 필터 + 좋아요순 | 36.7ms (Full Scan) | 1.7ms (brand_id 인덱스) | ~21배 |
| 전체 + 좋아요순 | **66ms** (Full Scan) | **0.156ms** (복합 인덱스) | **~420배** |

### 최종 인덱스 설계

```sql
-- FK 인덱스 (브랜드 필터용)
CREATE INDEX idx_product_brand_id ON product (brand_id);

-- 복합 인덱스 3개 (목록 조회의 공통 필터 + 정렬 최적화용)
CREATE INDEX idx_product_status_display_like ON product (status, display_yn, like_count DESC);
CREATE INDEX idx_product_status_display_created ON product (status, display_yn, created_at DESC);
CREATE INDEX idx_product_status_display_price ON product (status, display_yn, price);
```

### 배운 점

- **3,000건 vs 10만건** — 개발 환경의 적은 데이터에서는 Full Table Scan이어도 1~3ms밖에 안 걸린다. 실제 규모에 가까운 데이터로 테스트해야 성능 문제를 발견할 수 있다.
- **filesort != 인덱스 미사용** — `Using filesort`가 뜬다고 해서 인덱스를 전혀 사용하지 않은 것은 아니다. 필터링에는 인덱스를 사용했지만, ORDER BY를 인덱스 순서로 해결하지 못해 별도 정렬이 발생한 것일 수 있다. 인덱스 활용은 검색(필터링)과 정렬 두 단계로 나눠서 봐야 한다.
- **선두 컬럼 원칙** — `brand_id`를 선두에 둔 인덱스는 브랜드 필터가 없는 전체 조회를 해결하지 못했다. 현재 쿼리 패턴에서는 `status, display_yn`을 선두에 두고 정렬 컬럼을 결합한 복합 인덱스가 전체 조회 병목 해결에 더 적합했다. 현재 쿼리 패턴과 데이터 분포, 변화 가능성까지 고려해서 선두 컬럼을 결정해야 한다.
- **단일 인덱스 vs 복합 인덱스** — 단일 인덱스만으로도 현재 데이터 분포에서는 옵티마이저가 어느 정도 감당했지만, 필터와 정렬을 함께 안정적으로 처리하려면 복합 인덱스가 더 유리했다. 시즌 종료로 ON_SALE 비율이 변하거나 display_yn = 'N'이 늘어나면 단일 인덱스의 성능이 불안정해질 수 있다.
- **deleted_at은 인덱스에서 제외** — 대부분이 NULL이라 선두 컬럼으로 둘 이점이 작았고, 현재 쿼리에서는 다른 조건으로 충분히 후보를 줄일 수 있어 인덱스 후 필터로 처리해도 충분했다. 다만 soft delete 데이터가 대량 축적되면 재검토가 필요하다.
- **DESC 인덱스** — MySQL 8.0부터 실제로 내림차순 B-Tree를 지원한다. ASC 인덱스에서 DESC 정렬 시 Backward index scan이 발생하는데, DESC 인덱스를 걸면 reverse scan이 제거되는 것은 확인했다. LIMIT 20 수준에서는 체감 차이가 거의 없었지만, 더 많은 범위를 스캔하는 쿼리에서는 차이가 생길 가능성이 있다.

---

## 2. 좋아요 수 정렬 구조 — 비정규화 선택과 그 이유

### 무엇을 했는가

`like_count`를 Product 테이블에 비정규화 컬럼으로 두고, 좋아요 등록/취소 시 원자적 UPDATE로 카운트를 동기화했다. Materialized View 방식과 트레이드오프를 비교하고, EXPLAIN으로 성능 차이를 직접 확인했다.

### 비정규화를 선택한 핵심 이유

인덱스 설계와 직결되기 때문이다.
- **비정규화**: `(status, display_yn, like_count DESC)` 복합 인덱스 → 필터 + 정렬이 같은 테이블, 같은 인덱스 → LIMIT 20 수준에서 조기 종료하여 0.2ms
- **MV**: 필터 컬럼은 product에, 정렬 컬럼은 summary에 분산 → 단일 테이블의 복합 인덱스 하나로 필터와 정렬을 동시에 해결하기 어려움 → 조인 후 추가 정렬 비용이 발생할 가능성이 높음

```java
// 단일 UPDATE문 내에서 read-modify-write가 원자적으로 수행되므로,
// 이 쿼리 간의 동시 실행에서는 Lost Update가 방지된다.
@Query("UPDATE Product p SET p.likeCount = p.likeCount + 1 WHERE p.id = :productId")
int incrementLikeCount(@Param("productId") Long productId);

@Query("UPDATE Product p SET p.likeCount = p.likeCount - 1 WHERE p.id = :productId AND p.likeCount > 0")
int decrementLikeCount(@Param("productId") Long productId);
```

### 배운 점

- **비정규화의 트레이드오프** — 조회 성능은 최상이지만, 인기 상품에 좋아요가 몰리면 같은 row에 UPDATE 경합(Hot Row)이 발생할 수 있다. 현재 규모에서는 문제없지만, 트래픽이 커지면 Redis를 활용한 Write-Back(Redis INCR → 비동기 DB 반영)이나 분산 카운터 전략이 대안이 된다.
- **MySQL 8.0에 네이티브 MV가 없다** — MV를 구현하려면 결국 별도 테이블 + 배치/스케줄러를 직접 만들어야 하며, 사전 계산된 데이터를 저장한다는 점에서 비정규화된 읽기 모델에 가깝다.
- **정합성 리스크는 관리 가능** — 정기 배치로 likeCount를 Like 테이블 기준으로 재계산하여 보정할 수 있다.

---

## 3. 캐시 적용 — 로컬 캐시(Caffeine) vs Redis 캐시

### 무엇을 했는가

두 가지 캐시 방식을 **각각 별도로 구현**하여 트레이드오프를 직접 확인했다.
- **1단계**: Caffeine 로컬 캐시를 별도 API 엔드포인트로 구현
- **2단계**: Redis 캐시를 기존 API에 Cache-Aside 패턴으로 적용
- **3단계**: E2E 환경에서 50회 반복 성능 비교 테스트 수행

### 성능 비교 결과
로컬 캐시가 Redis보다 빠를 가능성은 충분히 예상할 수 있었지만,
실제로 어느 정도 차이가 나는지 수치로 확인하는 것은 의미가 있었다.
No Cache일 때와의 속도 차이도 수치로 직접 보니 캐싱의 효과를 더 체감했다.

**테스트 환경 참고:** Testcontainers 기반 로컬 Docker 환경에서 측정한 수치이며, Redis도 같은 로컬 머신에서 실행되므로 네트워크 latency가 거의 0에 가깝다. 실제 분리된 Redis 서버 환경에서는 Redis 수치가 더 높아질 수 있다.

| 구분 | 평균 | p50 | p95 | p99 |
|------|------|-----|-----|-----|
| No Cache (DB 직접) | 12.37ms | 11.98ms | 18.31ms | 29.18ms |
| Redis Cache Hit | 5.83ms | 5.32ms | 8.25ms | 18.24ms |
| Local Cache Hit | **1.39ms** | **1.32ms** | **1.84ms** | **2.46ms** |

### 최종 선택: Redis Cache

성능만 보면 Local Cache가 압도적이지만, **Redis Cache를 선택**했다.

| 판단 기준 | Local Cache | Redis Cache | 비중 |
|----------|-------------|-------------|------|
| Cache Hit 속도 | **1.39ms** | 5.83ms | 낮음 — 서버 내부 처리 시간 차이는 있었지만, 전체 사용자 응답 시간 관점에서는 운영 복잡도를 뒤집을 만큼 큰 차이라고 보긴 어려웠다 |
| 서버 간 정합성 | 불일치 발생 | **일관성 보장** | **높음** |
| 캐시 무효화 | 해당 서버만 | **전체 서버** | **높음** |
| 배포 안정성 | Cold Start | **캐시 유지** | 중간 |
| 확장성 | 서버별 독립 | **공유 캐시** | **높음** |

**"속도 4배 차이"보다 "데이터 일관성"과 "운영 안정성"의 가치가 더 크다.**

### 주요 설계 결정

| 결정 | 내용 | 이유 |
|------|------|------|
| TTL | 목록 3분, 상세 10분 | 목록은 좋아요 변동으로 정렬 변경 가능, 상세는 변경 드물어 길게 |
| 무효화 전략 | 상세: 명시적 삭제, 목록: TTL 의존 | 상세는 키가 단순(`product:detail:{id}`), 목록은 키 조합이 복잡 |
| 페이지 제한 | 3페이지(0~2)까지만 캐싱 | 일반적으로 이커머스에서 1~3페이지가 전체 조회 트래픽의 80~90%를 차지한다는 경험칙에 기반. 깊은 페이지까지 캐싱하면 키 수 폭발 + Redis 메모리 낭비 |
| 장애 대응 | try-catch fallback | Redis 장애 시 DB로 자동 전환, 서비스 중단 방지 |
| 직렬화 | Jackson JSON | Redis CLI에서 값 확인 가능, 디버깅 용이 |

### 배운 점

- **인덱스와 캐시는 보완재** — 인덱스가 쿼리를 빠르게 만드는 것이라면, 캐시는 쿼리 자체를 실행하지 않는 것이다. 인덱스 없이 캐시만 도입하면 Cache Miss 시 느린 쿼리가 그대로 실행된다.
- **캐시 무효화는 정말 어렵다** — 목록 캐시의 키가 keyword x sort x brandId x page x size 조합이라 명시적 삭제가 비현실적이다. `KEYS` 명령은 O(N)으로 운영 환경에서 위험하다. 실무에서는 `SCAN` 명령(커서 기반, 점진적 순회)을 사용하거나, 키 설계 시 Hash 구조를 활용해서 특정 그룹의 캐시를 한 번에 삭제할 수 있게 하는 방법이 있다.
- **Cache Stampede** — TTL 만료 직후 동시에 수백 요청이 모두 Cache Miss를 겪고 DB로 폭주할 수 있다. 락 기반 갱신이나 TTL jitter로 대응할 수 있다.
- **Cache Miss 비용** — Redis Cache Miss(39.86ms, 별도 성능 비교 테스트에서 측정)는 No Cache(12.37ms)보다 오히려 높다. DB 조회 외에 캐시 저장, 직렬화, 네트워크 왕복 등의 비용이 추가되기 때문이다. 동일 키에 대한 재요청이 충분히 발생한다면, 첫 Miss의 추가 비용은 이후 Hit 성능(5.83ms)으로 회수될 수 있다.
- **Spring AOP 프록시의 공통 제약 (Self-invocation)** — `@Cacheable`, `@Transactional`, `@Async` 등 Spring AOP 기반 어노테이션은 프록시를 통해 동작하므로, 같은 클래스 내부에서 `this.method()`로 호출하면 프록시를 거치지 않아 동작하지 않는다. `@Cacheable` 고유의 문제가 아니라 Spring AOP 전체의 공통 특성이다.
- **SpEL에서 null 처리** — SpEL에서 `String.valueOf(...)`를 사용할 때 null 값이 들어오면, 문맥에 따라 의도하지 않은 오버로드가 선택되면서 예외가 발생할 수 있었다. null-safe 삼항 연산자로 명시적으로 처리하여 우회했다.

### 캐시 웜업 (Cache Warm-Up)

Cache-Aside 패턴에서 첫 번째 요청은 항상 Cache Miss다. 서버 재시작(배포)이나 Redis 장애 복구 직후, 동시에 많은 요청이 DB로 직행하는 **Cache Stampede** 문제를 방지하기 위해 캐시 웜업을 적용했다.

- **`ApplicationReadyEvent`를 선택** — `@PostConstruct`는 Bean 생성 직후 실행되어 DB/Redis 준비가 보장되지 않지만, `ApplicationReadyEvent`는 모든 초기화 완료 후 실행되어 안전하다.
- **웜업 범위는 최소한으로** — 기본 정렬(LATEST) 0~2페이지 + 첫 페이지 노출 상품의 상세만 웜업. 모든 조합을 웜업하면 오히려 기동 시 DB 부하가 커진다.
- **웜업 실패가 서비스 기동을 막지 않는다** — 각 항목마다 try-catch로 감싸서, 실패해도 다음 항목으로 진행한다.

---

## 4. 코드 리뷰 반영 — 실무적 개선 포인트

### 배운 점

- **RESTful API 설계** — 좋아요 취소를 `PUT`으로 매핑했으나, 리소스 삭제/비활성화이므로 `DELETE`가 더 적합했다. HTTP 메서드의 의미를 정확히 반영해야 한다.
- **URL 설계와 보안** — `/users/{userId}/likes`에서 `userId`를 PathVariable로 받지만 실제로는 `@LoginMember`만 사용하고 있어, 다른 사용자 데이터에 접근 가능한 것처럼 보이는 보안 혼란이 있었다. `/me/likes`로 변경하여 의미를 명확히 했다.
- **Cross-Domain 의존성 제거 (DDD)** — `LikeService`(Like 도메인)가 `ProductRepository`(Product 도메인)에 직접 의존하고 있었다. `LikeService`는 Like 엔티티의 상태 전환만 담당하고, `likeCount` 증감은 `ProductFacade`(Application Layer)에서 `ProductService`를 통해 처리하도록 분리했다. Bounded Context 간 결합도를 낮추는 것이 유지보수에 유리하다.
- **null-safety (Fail-Fast)** — `CacheManager.getCache()`가 `@Nullable`을 반환하지만 null 체크 없이 사용하고 있었다. `Objects.requireNonNull()`로 빈 설정 누락 시 빠르게 실패하도록 개선했다.
- **캐시 무효화 E2E 테스트** — 관리자 상품 수정/삭제 후 사용자 API에서 최신 데이터가 반환되는지 E2E 레벨 검증이 없었다. 수정 → 캐시 삭제 → 재조회 시나리오를 E2E 테스트로 추가했다.

---

## 5. 테스트 보완

### 추가한 테스트

| 영역 | 추가 테스트 | 검증 포인트 |
|------|-----------|-----------|
| 상품 검색 통합 | Q4(전체+최신순), Q5(전체+가격순) | 모든 정렬 조건 x 브랜드 필터 유무 조합 커버 |
| ProductFacade 단위 | Cache Miss/Hit 분기, 페이지 제한 | Redis Cache-Aside 패턴 로직 검증 |
| AdminProductFacade 단위 | 수정/삭제 시 캐시 evict | 캐시 무효화 동작 검증 |
| 로컬 캐시 E2E | 8개 테스트 | 캐시 Hit/Miss, 페이지 제한, 키 분리, 예외 처리 |
| Redis 캐시 E2E | 7개 테스트 | 캐시 저장, Hit, 키 분리, 페이지 제한, 예외 처리 |
| 성능 비교 | 5개 시나리오 | No Cache vs Redis vs Local의 정량적 성능 수치 |
| 캐시 무효화 E2E | 2개 시나리오 | 관리자 수정/삭제 후 사용자 조회 시 최신 데이터 반환 검증 |

---

## 6. 이번 주를 돌아보며

### 가장 많이 한 것

코드를 짜는 것보다 **EXPLAIN 결과를 보면서 "왜?"를 반복한 것**이 가장 많았다.
인덱스의 동작 원리, MySQL 옵티마이저의 판단 기준, 데이터 분포가 성능에 미치는 영향을 직접 확인할 수 있었다.

### 개발 환경의 "빠르다"를 믿으면 안 된다

데이터가 수십 건일 때는 Full Table Scan이든 인덱스 스캔이든 체감 차이가 없다.
하지만 10만건에서는 66ms vs 0.2ms, 300배 차이가 났다.
기능 구현이 끝났다고 "잘 동작하네"로 넘기지 않고, 실제 규모에 가까운 데이터로 검증하는 습관이 중요하다.

### 트레이드오프를 고민하는 과정 자체가 설계

- 단일 인덱스 vs 복합 인덱스
- 비정규화 vs Materialized View
- 로컬 캐시 vs Redis 캐시

정답이 하나가 아니었다. 현재 쿼리 패턴, 데이터 분포, 서비스 규모, 그리고 앞으로의 변화 가능성까지 고려해서 트레이드오프를 판단하는 과정이 설계의 본질이라는 걸 느꼈다.

### 향후 개선 포인트

- 인덱스 추가 후 INSERT/UPDATE 쓰기 성능 영향을 정량적으로 측정해보기
- Cache Stampede 대응 전략(락 기반 갱신, TTL jitter) 실제 적용
- L1(Caffeine) + L2(Redis) 2계층 캐시 구조 실험
- 트래픽 증가 시 Redis 카운터 + 비동기 DB 반영 전략 검토
