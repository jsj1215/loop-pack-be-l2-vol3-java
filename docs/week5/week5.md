
## 과제 정보

**① 상품 목록 조회 성능 개선**

- 상품 데이터를 10만개 이상 준비합니다 (각 컬럼의 값은 다양하게 분포하도록 합니다 )
- 브랜드 필터 + 좋아요 순 정렬 기능을 구현하고, **`EXPLAIN`** 분석을 통해 인덱스 최적화를 수행합니다.
- 성능 개선 전후 비교를 포함해 주세요.

**② 좋아요 수 정렬 구조 개선**

- **비정규화**(**`like_count`**) 혹은 **MaterializedView** 중 하나를 선택하여 좋아요 수 정렬 성능을 개선합니다.
- 좋아요 등록/취소 시 count 동기화 처리 방식이 누락되어 있다면 이 또한 함께 구현합니다.

**③ 캐시 적용**

- 상품 상세 API 및 상품 목록 API에 **Redis 캐시**를 적용합니다.
- TTL 설정, 캐시 키 설계, 무효화 전략 중 하나 이상 포함해 주세요.

---

## Checklist

### 🔖 Index

- [ ]  상품 목록 API에서 brandId 기반 검색, 좋아요 순 정렬 등을 처리했다
- [ ]  조회 필터, 정렬 조건별 유즈케이스를 분석하여 인덱스를 적용하고 전 후 성능비교를 진행했다

### ❤️ Structure

- [ ]  상품 목록/상세 조회 시 좋아요 수를 조회 및 좋아요 순 정렬이 가능하도록 구조 개선을 진행했다
- [ ]  좋아요 적용/해제 진행 시 상품 좋아요 수 또한 정상적으로 동기화되도록 진행하였다

### ⚡ Cache

- [ ]  Redis 캐시를 적용하고 TTL 또는 무효화 전략을 적용했다
- [ ]  캐시 미스 상황에서도 서비스가 정상 동작하도록 처리했다.

---

## 성능 개선 전후 비교

### 테스트 환경
- 데이터: 브랜드 80개, 상품 100,000건, 상품옵션 191,308건
- 데이터 분포: like_count 롱테일(0~9,980), status ON_SALE 85%, displayYn Y 90%
- MySQL 8.0, Docker 로컬 환경

### 쿼리 유즈케이스

| # | 쿼리 | 설명 |
|---|------|------|
| Q1 | 브랜드 필터 + 좋아요순 정렬 (LIMIT 20) | 특정 브랜드 상품을 인기순(like_count DESC)으로 조회 |
| Q2 | 브랜드 필터 + 최신순 정렬 (LIMIT 20) | 특정 브랜드 상품을 최신순(created_at DESC)으로 조회 |
| Q3 | 전체 목록 + 좋아요순 정렬 (LIMIT 20) | 브랜드 필터 없이 인기순(like_count DESC) 조회 |
| Q4 | 전체 목록 + 최신순 정렬 (LIMIT 20) | 브랜드 필터 없이 최신순(created_at DESC) 조회 |
| Q5 | 전체 목록 + 가격순 정렬 (LIMIT 20) | 브랜드 필터 없이 가격순(price ASC) 조회 |
| Q6 | 브랜드 필터 + COUNT | 페이징을 위한 총 건수 조회 |

### 인덱스 적용 전 (Before)

| 쿼리 | 접근 방식 | 스캔 rows | 정렬 | 실행 시간 |
|------|----------|----------|------|----------|
| Q1 | Index lookup (FK brand_id) | 1,276 | filesort (in-memory) | 1.81ms |
| Q2 | Index lookup (FK brand_id) | 1,276 | filesort (in-memory) | 1.72ms |
| Q3 | **Table scan (Full)** | **100,000** | **filesort** | **62.3ms** |
| Q4 | **Table scan (Full)** | **100,000** | **filesort** | Q3과 동일 수준 (미측정) |
| Q5 | **Table scan (Full)** | **100,000** | **filesort** | Q3과 동일 수준 (미측정) |
| Q6 | Index lookup (FK brand_id) | 1,276 | - | 2.43ms |

### 인덱스 적용 후 (After)

적용 인덱스:
```sql
CREATE INDEX idx_product_status_display_like ON product (status, display_yn, like_count);
CREATE INDEX idx_product_status_display_created ON product (status, display_yn, created_at);
CREATE INDEX idx_product_status_display_price ON product (status, display_yn, price);
```

| 쿼리 | 옵티마이저 선택 인덱스 | 접근 방식 | 스캔 rows | 정렬 | 실행 시간 |
|------|---------------------|----------|----------|------|----------|
| Q1 | FK (brand_id) | Index lookup | 1,276 | filesort | 1.68ms |
| Q2 | FK (brand_id) | Index lookup | 1,276 | filesort | 1.92ms |
| Q3 | **idx_status_display_like (reverse)** | **Index lookup** | **20** | **인덱스 순서** | **0.201ms** |
| Q4 | **idx_status_display_created (reverse)** | **Index lookup** | **20** | **인덱스 순서** | **0.185ms** |
| Q5 | **idx_status_display_price** | **Index lookup** | **20** | **인덱스 순서** | **0.156ms** |
| Q6 | FK (brand_id) | Index lookup | 1,276 | - | 2.47ms |

### 분석

**인덱스 적용 전 병목:**
- Q1, Q2, Q6: FK 인덱스(`brand_id`)로 인해 특정 브랜드 조회는 1,276건만 스캔하여 비교적 빠름 (1~2ms)
- **Q3, Q4, Q5가 핵심 병목**: 브랜드 필터 없이 전체 목록 정렬 시 Full Table Scan (100,000건) + filesort 발생 → 62.3ms

**인덱스 적용 후 개선:**
- Q3, Q4, Q5: 복합 인덱스(`status, display_yn, 정렬컬럼`)를 활용하여 **LIMIT 20 수준에서 조기 종료 + filesort 제거** → 0.1~0.2ms (**약 300배 개선**)
- Q1, Q2, Q6: 옵티마이저가 기존 FK 인덱스를 선택하여 기존과 동일한 성능 유지 (1~2ms)

---

## 인덱스 설계 검토

### 현재 인덱스 현황
- PK: `id` (AUTO_INCREMENT)
- FK: `brand_id` (Hibernate 자동 생성) — 브랜드 필터 시 활용됨

### 현재 쿼리의 실행 흐름 (Q1: 브랜드 필터 + 좋아요순)
1. FK 인덱스(`brand_id`)로 해당 브랜드의 상품 1,276건 조회
2. `status = 'ON_SALE'`, `display_yn = 'Y'`, `deleted_at IS NULL` 조건으로 필터링 → 984건
3. `like_count DESC`로 메모리 filesort 수행
4. LIMIT 20 적용

### 복합 인덱스 후보

```sql
-- 옵션 A: 필터 조건 + 정렬 컬럼 포함
CREATE INDEX idx_product_brand_status_display_like
ON product (brand_id, status, display_yn, like_count DESC);

-- 옵션 B: 필터 조건만
CREATE INDEX idx_product_brand_status_display
ON product (brand_id, status, display_yn);
```

**옵션 A** — 필터 + 정렬까지 인덱스에 포함
- filesort 제거 가능 (인덱스 순서대로 읽어서 정렬 생략)
- 필터 조건도 인덱스에서 처리하여 불필요한 row 스캔 감소 (1,276 → ~984)

**옵션 B** — 필터 조건만 인덱스에 포함
- 스캔 row는 줄지만 filesort는 여전히 발생

### Descending Index (MySQL 8.0+)

MySQL 8.0부터 **Descending Index**를 지원한다.
이전 버전(5.7 이하)에서는 `DESC`를 구문상 허용하지만 실제로는 무시하고 ASC로만 저장했다.
8.0부터는 실제로 내림차순으로 B-Tree를 구성한다.

```sql
-- MySQL 8.0+에서 유효
CREATE INDEX idx_example ON product (brand_id, like_count DESC);
```

**ASC 인덱스로 DESC 정렬을 할 수 있는가?**

가능하다. MySQL은 **Backward Index Scan**으로 인덱스를 역방향으로 읽는다.
filesort 없이 인덱스를 타는 것은 동일하다.

| 인덱스 방향 | 쿼리 정렬 | 스캔 방식 |
|-----------|----------|----------|
| ASC | ORDER BY like_count DESC | Backward Index Scan (역방향) |
| DESC | ORDER BY like_count DESC | Forward Index Scan (순방향) |

LIMIT 20 수준에서는 체감 차이가 거의 없었다.
다만 DESC 인덱스 적용 후 reverse scan이 제거되는 것은 확인했으며,
더 많은 범위를 스캔하는 쿼리에서는 차이가 생길 가능성이 있다.

### MySQL의 정렬 처리 방식

MySQL이 ORDER BY를 처리하는 방식은 두 가지이다.

**1. filesort (메모리/디스크 정렬)**
- 조건에 맞는 row를 먼저 다 가져온 다음, 정렬 알고리즘으로 순서를 매김
- "filesort"라는 이름이지만 데이터가 작으면 메모리에서 처리됨
- 데이터가 `sort_buffer_size`를 초과하면 디스크 임시 파일을 사용

**2. 인덱스 순서로 읽기 (정렬 생략)**
- 인덱스가 이미 정렬되어 있으므로, 순서대로 읽기만 하면 됨
- 별도 정렬 작업이 필요 없음
- LIMIT과 결합하면 필요한 건수만 읽고 바로 멈출 수 있어서 효과가 큼

예시: `(brand_id, status, display_yn, like_count DESC)` 인덱스가 있을 때

```
인덱스 내부 (brand_id=5, status='ON_SALE', display_yn='Y' 기준):
like_count: 9800, 8500, 7200, 5000, ... , 3, 1, 0
            ↑ 이미 정렬된 상태 → 앞에서 20건만 읽으면 LIMIT 20 완료
```

반면 `created_at DESC`로 정렬하고 싶은데 인덱스에는 `like_count`만 있으면,
인덱스 순서가 안 맞으므로 결국 데이터를 다 읽고 filesort를 해야 한다.

### 고려사항

#### `deleted_at IS NULL` — 인덱스에서 제외
- `deleted_at`은 대부분이 NULL이라 선두 컬럼으로 둘 이점이 작다고 판단했다
- 현재 쿼리에서는 `status`, `display_yn` 등 다른 조건으로 충분히 후보를 줄일 수 있어, 인덱스 후 필터로 처리해도 성능상 문제가 크지 않았다
- 인덱스 컬럼이 늘어나면 인덱스 크기 증가 + 쓰기(INSERT, UPDATE) 부담만 커짐
- 다만 soft delete된 데이터가 대량으로 축적되면 필터링 비용이 커질 수 있으므로, 데이터 분포 변화에 따라 인덱스 재설계를 검토해야 한다

#### 정렬 컬럼 — 인덱스에 포함
- filesort를 제거할 수 있음
- LIMIT 20과 결합하면 인덱스에서 20건만 읽고 바로 멈출 수 있어서 효과가 큼

#### 정렬 조건이 여러 개인 경우 — 인덱스 전략

현재 정렬 조건이 3개이다:
- `LIKE_DESC` → `like_count DESC`
- `LATEST` → `created_at DESC`
- `PRICE_ASC` → `price ASC`

정렬 컬럼별로 인덱스를 각각 만들면 모든 정렬에서 filesort를 제거할 수 있지만,
인덱스 개수가 늘어나면 쓰기 성능이 떨어진다.

```sql
-- 정렬 조건별 인덱스 (3개)
CREATE INDEX idx_product_brand_like ON product (brand_id, status, display_yn, like_count DESC);
CREATE INDEX idx_product_brand_created ON product (brand_id, status, display_yn, created_at DESC);
CREATE INDEX idx_product_brand_price ON product (brand_id, status, display_yn, price ASC);
```

실무에서는 **모든 조합을 인덱스로 커버하지 않는다.** 일반적인 전략:
1. **핵심 유즈케이스 우선** — 사용 빈도가 높은 조합 1~2개만 인덱스를 만들고, 나머지는 filesort 감수
2. **캐시로 보완** — 전체 인기순 같이 실시간일 필요 없는 정렬은 Redis 캐시로 커버
3. **검색 엔진 분리** — 대규모 서비스에서는 Elasticsearch 등으로 다양한 필터/정렬 조합을 처리 (이번 과제 범위 밖)

#### Q3(전체 + 좋아요순) 병목 — 복합 인덱스의 선두 컬럼 원칙

`(brand_id, status, display_yn, like_count DESC)` 인덱스를 만들면:
- `brandId`가 **있을 때**: 인덱스 선두 컬럼 매칭 → 잘 탐
- `brandId`가 **없을 때**: 선두 컬럼(`brand_id`)을 건너뛰어야 함 → 인덱스를 효율적으로 못 탐

하나의 복합 인덱스로 두 케이스를 동시에 최적화하기 어렵다.
Q3은 별도 인덱스 추가 또는 캐시(③번)로 해결하는 방향을 검토한다.

#### 브랜드 필터 있을 때 filesort 비용

`ORDER BY ... LIMIT 20` 쿼리에서 정렬은 LIMIT 전에 수행된다.
"20건을 가져와서 정렬"이 아니라 **"조건에 맞는 전체 row를 정렬한 다음 20건을 잘라내는"** 순서이다.

따라서:
- 조건에 맞는 데이터가 984건이면 → 984건 filesort 후 20건 반환
- 조건에 맞는 데이터가 10만건이면 → 10만건 filesort 후 20건 반환

인덱스에 정렬 컬럼이 있으면 이미 정렬된 순서로 읽으므로 20건 읽는 순간 바로 멈출 수 있다.

다만 **브랜드 필터가 있는 경우 984건 filesort는 1.81ms로 이미 충분히 빠르다.**
이 정도 데이터량에서는 정렬 인덱스 최적화 효과가 크지 않다.
**진짜 병목은 브랜드 필터 없이 전체 조회하는 Q3 (76,525건 filesort → 62.3ms)** 이다.

---

## 단일 정렬 컬럼 인덱스 테스트

### 접근 방식

복합 인덱스 대신, 정렬 기준 컬럼별로 단일 인덱스 3개를 생성하여
MySQL 옵티마이저가 상황에 따라 어떤 인덱스를 선택하는지 테스트한다.

```sql
CREATE INDEX idx_product_like_count ON product (like_count);
CREATE INDEX idx_product_created_at ON product (created_at);
CREATE INDEX idx_product_price ON product (price);
```

### 옵티마이저 인덱스 선택 결과 (EXPLAIN)

| 케이스 | 브랜드 필터 | 정렬 | 옵티마이저가 선택한 인덱스 | 정렬 방식 |
|--------|-----------|------|------------------------|----------|
| 1 | O | like_count DESC | FK (brand_id) | filesort |
| 2 | X | like_count DESC | idx_product_like_count (reverse) | 인덱스 순서 |
| 3 | O | created_at DESC | FK (brand_id) | filesort |
| 4 | X | created_at DESC | idx_product_created_at (reverse) | 인덱스 순서 |
| 5 | O | price ASC | FK (brand_id) | filesort |
| 6 | X | price ASC | idx_product_price | 인덱스 순서 |

### 분석

**옵티마이저가 브랜드 필터 유무에 따라 인덱스를 자동으로 나눠서 선택한다:**

- **브랜드 필터 있을 때** → FK 인덱스(`brand_id`)로 row를 먼저 줄인 뒤 filesort
    - 1,276건 중 984건만 정렬하므로 1~2ms로 충분히 빠름
    - 정렬 인덱스를 안 타도 성능 문제 없음

- **브랜드 필터 없을 때** → 정렬 컬럼 인덱스를 타서 순서대로 읽으며 필터 체크
    - 인덱스 순서대로 읽으면서 `status`, `display_yn`, `deleted_at` 조건 체크
    - ON_SALE 85% * Y 90% ≒ 76%가 조건 통과 → 약 26~27건만 읽으면 LIMIT 20 충족
    - Full Table Scan + filesort (62.3ms) 를 제거

**결론: 현재 데이터 분포에서는 옵티마이저가 단일 인덱스 3개로 어느 정도 감당했지만,
필터와 정렬을 함께 안정적으로 처리하려면 복합 인덱스가 더 유리하다.** (아래 최종 검토에서 상세 비교)

---

## 인덱스 전략 최종 검토: 단일 인덱스 vs 복합 인덱스

### 단일 정렬 인덱스의 한계 — `status`, `display_yn` 선택도 문제

단일 인덱스 테스트에서는 `status`와 `display_yn`의 선택도가 낮아서 (ON_SALE 85%, Y 90%)
인덱스에 포함하지 않아도 괜찮다고 판단했다.

그러나 **실제 커머스 환경에서는 데이터 분포가 수시로 변한다:**
- 시즌 종료 → 대량 `DISCONTINUED` 전환
- 재고 소진 → `SOLD_OUT` 증가
- 임시 비노출 → `display_yn = 'N'` 증가

ON_SALE + Y 비율이 50% 이하로 떨어지면 `status`와 `display_yn`의 필터링 효과가 커진다.
**데이터 분포 변화에 안정적으로 대응하려면 복합 인덱스가 더 적합하다.**

### 복합 인덱스 선두 컬럼 선택: `brand_id` vs `status`

#### 옵션 A: `brand_id` 선두

```sql
CREATE INDEX idx_brand_like ON product (brand_id, status, display_yn, like_count);
CREATE INDEX idx_brand_created ON product (brand_id, status, display_yn, created_at);
CREATE INDEX idx_brand_price ON product (brand_id, status, display_yn, price);
```

- 브랜드 필터 **있을 때**: 선두 컬럼 매칭 → 완벽하게 동작
- 브랜드 필터 **없을 때**: 선두 컬럼 `brand_id`를 건너뛰어야 함 → 인덱스 효율 떨어짐

#### 옵션 B: `status` 선두

```sql
CREATE INDEX idx_status_like ON product (status, display_yn, like_count);
CREATE INDEX idx_status_created ON product (status, display_yn, created_at);
CREATE INDEX idx_status_price ON product (status, display_yn, price);
```

- 브랜드 필터 **없을 때**: `status`, `display_yn` 매칭 → 잘 동작
- 브랜드 필터 **있을 때**: `brand_id` 조건은 인덱스에 없으므로 행 단위 체크 필요
    - 단, 기존 FK 인덱스(`brand_id`)가 이미 있고 1~2ms로 충분히 빠름

#### 비교 정리

| 전략 | 브랜드 O + 정렬 | 브랜드 X + 정렬 | 비고 |
|------|---------------|---------------|------|
| A: `brand_id` 선두 복합 3개 | 완벽 최적화 | 선두 컬럼 못 탐 (비효율) | 전체 조회 병목 해결 안 됨 |
| B: `status` 선두 복합 3개 | FK 인덱스 활용 (1~2ms) | 완벽 최적화 | 전체 조회 병목 해결 |

하나의 복합 인덱스로 두 케이스(브랜드 필터 유/무)를 동시에 완벽 최적화하기는 어렵다.

### 결론

**현재 쿼리 패턴에서는 옵션 B (`status` 선두 복합 인덱스 3개)가 전체 조회 병목 해결에 더 적합했다.**

`status`를 선두에 둔 것이 항상 최선이라는 의미가 아니라,
현재 조회 패턴에서 `status + display_yn + 정렬 컬럼` 조합이 `ORDER BY + LIMIT`에 잘 맞았기 때문이다.

이유:
1. **핵심 병목 해결** — Q3(전체 + 좋아요순, 62.3ms)이 가장 큰 병목이므로 이를 우선 최적화
2. **브랜드 필터는 기존 FK 인덱스로 충분** — 이미 1~2ms로 빠르게 동작
3. **데이터 분포 변화에 안정적** — `status`, `display_yn`이 인덱스에 포함되어 있으므로 비율 변동에도 대응 가능

```sql
CREATE INDEX idx_product_status_display_like ON product (status, display_yn, like_count);
CREATE INDEX idx_product_status_display_created ON product (status, display_yn, created_at);
CREATE INDEX idx_product_status_display_price ON product (status, display_yn, price);
```

---

# 좋아요 수 정렬 구조 개선: 비정규화 vs Materialized View

## 배경

상품 목록 조회 시 좋아요 순 정렬 성능을 개선하기 위해, 두 가지 접근 방식을 비교 검토한다.

- **비정규화**: Product 테이블에 `like_count` 컬럼을 두고 like/unlike 시 단일 UPDATE문으로 동기화
- **Materialized View**: Like 테이블의 집계 결과를 별도 테이블/뷰로 관리

---

## 현재 구현 (비정규화)

Product 엔티티에 `likeCount` 필드를 두고, 좋아요 등록/취소 시 원자적 UPDATE로 카운트를 증감한다.

```java
// ProductJpaRepository
@Query("UPDATE Product p SET p.likeCount = p.likeCount + 1 WHERE p.id = :productId")
int incrementLikeCount(@Param("productId") Long productId);

@Query("UPDATE Product p SET p.likeCount = p.likeCount - 1 WHERE p.id = :productId AND p.likeCount > 0")
int decrementLikeCount(@Param("productId") Long productId);
```

- `likeCount > 0` 조건으로 음수 방지
- 원자적 UPDATE로 Lost Update 방지 (낙관적 락 불필요)

---

## 트레이드오프 비교

### 비정규화 (`Product.likeCount`)

| 항목 | 설명 |
|------|------|
| **조회 성능** | 최상 - Product 테이블 단일 컬럼이므로 JOIN 없이 정렬/필터 가능 |
| **인덱스 활용** | `like_count DESC` 복합 인덱스로 정렬 쿼리 최적화 가능 |
| **실시간성** | 즉시 반영 - like/unlike 시점에 카운트 갱신 |
| **쓰기 비용** | 매 like/unlike마다 Product 테이블 UPDATE 발생 (write amplification) |
| **정합성 리스크** | 버그/장애 시 `likeCount`와 실제 Like 레코드 수 불일치 가능 |
| **동시성** | 인기 상품에 좋아요가 몰리면 같은 row에 UPDATE 경합 발생 (Hot Row) |
| **도메인 결합** | 좋아요 기능이 Product row에 락을 잡으므로 상품 수정과 간섭 가능 |
| **구현 복잡도** | 낮음 |

### Materialized View

| 항목 | 설명 |
|------|------|
| **조회 성능** | 좋음 - 별도 집계 테이블 JOIN 또는 단독 조회 |
| **인덱스 활용** | 집계 테이블에 인덱스 생성 가능 |
| **실시간성** | 갱신 주기만큼 지연 (eventual consistency) |
| **쓰기 비용** | Like 테이블만 INSERT/UPDATE, Product 테이블 미접촉 |
| **정합성** | 갱신 시점에는 원본 기준 정확한 집계 보장 |
| **동시성** | Product 테이블 경합 없음 - Hot Row 문제 해소 |
| **도메인 결합** | 좋아요 도메인이 상품 도메인에 의존하지 않음 (관심사 분리) |
| **구현 복잡도** | 높음 - MySQL 8.0에 네이티브 MV 미지원, 직접 구현 필요 |

### 종합 비교

| 기준 | 비정규화 | Materialized View |
|------|---------|-------------------|
| 조회 성능 | **최상** | 좋음 |
| 쓰기 성능 | 매번 UPDATE | Like 테이블만 접촉 |
| 데이터 정합성 | 불일치 가능 | **갱신 시 정확** |
| 실시간성 | **즉시 반영** | 갱신 주기만큼 지연 |
| 구현 복잡도 | **낮음** | 높음 |
| Hot Row 이슈 | 있음 | **없음** |

---

## MySQL 8.0에서의 Materialized View 한계

MySQL 8.0은 네이티브 Materialized View를 지원하지 않는다.
이를 구현하려면 아래와 같은 방법을 사용해야 한다.

```sql
-- 1. 별도 summary 테이블 생성
CREATE TABLE product_like_summary (
    product_id BIGINT PRIMARY KEY,
    like_count INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL
);

-- 2. 주기적으로 집계 갱신 (배치 or 스케줄러)
REPLACE INTO product_like_summary (product_id, like_count, updated_at)
SELECT product_id, COUNT(*), NOW()
FROM likes
WHERE like_yn = 'Y'
GROUP BY product_id;
```

결국 이것도 **"비정규화 테이블을 주기적으로 갱신"**하는 것이므로, 비정규화의 변형에 해당한다.

---

## 결론: 비정규화 선택

현재 프로젝트 상황에서 비정규화 방식이 더 적합하다고 판단한다.

### 선택 근거

1. **MySQL 8.0에 네이티브 MV가 없음**
- MV를 구현하려면 별도 summary 테이블 + Spring Batch/스케줄러를 직접 만들어야 하며, 이는 본질적으로 비정규화의 변형이다.

2. **실시간성 요구**
- 좋아요 수는 사용자에게 즉시 반영되는 것이 자연스러운 UX이다.
- MV 방식은 갱신 주기에 따라 "방금 눌렀는데 반영 안 됨" 문제가 발생할 수 있다.

3. **정합성 리스크는 관리 가능**
- 정기 배치로 `likeCount`를 Like 테이블 기준으로 재계산하여 보정할 수 있다.
- 예: 매일 새벽 `UPDATE product p SET p.like_count = (SELECT COUNT(*) FROM likes l WHERE l.product_id = p.id AND l.like_yn = 'Y')`

4. **Hot Row 문제의 현실적 규모**
- 현재 서비스 규모에서 단일 상품에 초당 수천 건의 좋아요가 몰리는 상황은 발생하기 어렵다.
- 향후 트래픽 증가 시에는 Redis 카운터 + 비동기 DB 반영 전략이 MV보다 효과적이다.

### Hot Row 문제가 심각해질 경우의 대안

비정규화도 MV도 아닌, **Redis 기반 카운터** 전략이 더 적합하다.

```
[요청] → Redis INCR product:{id}:like_count → 즉시 응답
        ↓ (비동기, 주기적)
      DB product.like_count 반영
```

- 즉시 반영 + 쓰기 경합 없음 + DB 부하 최소화
- 비정규화의 실시간성과 MV의 쓰기 분리 장점을 모두 취할 수 있다

---

## 블로그 리뷰 반영 수정 내역

### 텍스트 수정 완료 (27건)

| # | 항목 | 수정 내용 |
|---|------|----------|
| 1 | B-Tree → B+Tree | InnoDB 인덱스 명칭을 B+Tree로 정정, 리프 노드 링크드 리스트 특성 추가 |
| 2 | "반씩 쪼개서 찾기" | 다방향 탐색 트리(Multi-way Search Tree) 설명으로 수정 |
| 3 | log₂ 모순 해소 | 이진 탐색/B+Tree 비교를 분리하여 "3층" 설명과 일관성 확보 |
| 4 | 리프 노드 설명 | "실제 데이터 위치 포인터" → 클러스터드/세컨더리 구분 반영 |
| 5 | "물리적으로 저장" | "논리적 정렬, 페이지 분할 시 물리적 연속 아닐 수 있음" 명시 |
| 6 | EXPLAIN ANALYZE → EXPLAIN | 본문 전체에서 EXPLAIN으로 표현 통일 (4곳) |
| 7 | "브랜드 필터도 없고" | "브랜드 필터는 있지만 인덱스가 없어서"로 수정 |
| 8 | "래퍼런스 키" | "외래 키(Foreign Key)"로 용어 교정 |
| 9 | "좋아요만" | "전체 조회 + 좋아요순"으로 수정 (3곳) |
| 10 | "한 쿼리에서 인덱스를 하나만 사용" | Index Merge 예외를 고려한 표현으로 수정 |
| 11 | "정렬이 LIMIT보다 먼저 수행된다" | "인덱스로 해소 못 하면 후보 집합 전체 정렬 필요"로 완화 |
| 12 | "Forward scan으로 정상 동작" | EXPLAIN만으로 직접 확인 어렵다는 점 명시 |
| 13 | "49,518건을 모두 JOIN한 뒤 filesort" | EXPLAIN 추정치 기준임을 명시, 단정 표현 완화 |
| 14 | 선두 컬럼 카디널리티 리스크 | 낮은 카디널리티 선두 배치의 리스크와 전략적 선택 이유 부연 |
| 15 | 62.3ms 로컬 환경 한계 | Buffer Pool 캐싱, 실무 환경 차이 한 줄 추가 |
| 16 | 브랜드별 Data Skew | 특정 브랜드 편중 시 filesort 성능 저하 가능성 언급 |
| 17 | 커버링 인덱스 팁 | SELECT * 대신 필요한 컬럼만 명시하면 커버링 인덱스 유도 가능 |
| 18 | deleted_at soft delete 축적 | 대량 축적 시 인덱스 재설계 필요성 한 줄 추가 |
| 19 | OR 조건 표현 | "인덱스 사용이 어려움" → "Index Merge 가능하나 비효율적" |
| 20 | MV 비교 공정성 | 비정규화에 유리한 조건임을 명시, MV 최적화 여지 언급 |
| 21 | "20~40배" 수치 | "약 25~43배"로 정정 |
| 22 | 3,000건 생략 언급 | 이후 10만건 기준 분석임을 한 줄 추가 |
| 23 | "전체 카운트" 섹션 맥락 | 페이지네이션 용도 명시, Backward scan 설명 위치 분리 |
| 24 | Backward scan 톤 | "비효율적" → "대량 스캔 시 차이, 약 10~15% 수준" |
| 25 | Step 2→3 측정 오차 | 수치 변동은 측정 오차 범위라는 주석 추가 |
| 26 | Step 3 이미지 2장 설명 | 두 번째 이미지가 상세 실행 통계임을 한 줄 추가 |
| 27 | 비정규화 로우 락 보강 | 원자적 UPDATE 시 Row Lock 경합 + 상품 수정 간섭 가능성 명시 |

### 캡처/실행 필요 (직접 수정 필요 — 8건)

아래 항목들은 실제 DB 쿼리 실행 및 스크린샷 교체가 필요하여 텍스트 수정만으로 해결 불가.
상세 내용은 대화 이력 참조.

| # | 항목 |
|---|------|
| 1 | EXPLAIN ANALYZE FORMAT=TREE 결과 캡처로 이미지 교체 |
| 2 | 2.6 "전체 카운트" 섹션 이미지 교체 (올바른 쿼리 결과로) |
| 3 | "20건만 읽고 멈춘다" 증거 (EXPLAIN ANALYZE로 rows examined 확인) |
| 4 | "Forward scan" 증거 (DESC 인덱스 적용 후 EXPLAIN ANALYZE 결과) |
| 5 | Step 4 DESC 적용 전/후 실행 시간 비교 수치 추가 |
| 6 | Velog 표 렌더링 확인 |
| 7 | 2.6 섹션 이미지 교체 후 본문 최종 검토 |
| 8 | (선택) 인덱스 추가 후 INSERT 성능 측정 |

---

# 캐시 적용 — 로컬 캐시(Caffeine) + Redis 캐시

## 배경

인덱스 최적화로 상품 목록 조회 쿼리가 0.2ms까지 개선되었지만,
트래픽이 더 몰리면 **매 요청마다 DB 쿼리를 실행하는 것 자체**가 병목이 될 수 있다.
인덱스가 쿼리를 빠르게 만드는 것이라면, 캐시는 **쿼리 자체를 실행하지 않는 것**이다.

두 가지 캐시 방식을 비교 학습하기 위해,
로컬 캐시(Caffeine)와 Redis 캐시를 각각 별도 API로 구현하여 트레이드오프를 직접 확인한다.

---

## 1단계: 로컬 캐시 (Caffeine) 적용

### 전략 선택

| 결정 항목 | 상품 목록 (`productSearch`) | 상품 상세 (`productDetail`) |
|----------|--------------------------|--------------------------|
| **캐시 저장소** | Caffeine (JVM 로컬 메모리) | Caffeine (JVM 로컬 메모리) |
| **캐시 전략** | Cache-Aside (`@Cacheable`) | Cache-Aside (`@Cacheable`) |
| **TTL** | 3분 (`expireAfterWrite`) | 5분 (`expireAfterWrite`) |
| **최대 크기** | 1,000개 엔트리 | 5,000개 엔트리 |
| **무효화** | TTL 만료 기반 | TTL 만료 기반 |

- 상품 상세는 개별 상품별로 캐시 엔트리가 생성되므로 목록보다 `maximumSize`를 크게 설정
- 상세 정보는 변경 빈도가 더 낮으므로 TTL을 5분으로 설정

### 구현 내용

#### 의존성 추가

```kotlin
// apps/commerce-api/build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-cache")
implementation("com.github.ben-manes.caffeine:caffeine")
```

#### 캐시 설정 (`LocalCacheConfig.java`)

처음에는 `CaffeineCacheManager`로 단일 캐시(productSearch)만 구성했으나,
상품 상세 캐시(productDetail)를 추가하면서 **캐시별로 TTL과 maxSize를 다르게 설정**해야 했다.
`CaffeineCacheManager`는 모든 캐시에 동일한 설정만 적용 가능하므로,
**`SimpleCacheManager` + 개별 `CaffeineCache` 빌드** 방식으로 전환했다.

```java
@EnableCaching
@Configuration
public class LocalCacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(
                buildCache("productSearch", Duration.ofMinutes(3), 1_000),
                buildCache("productDetail", Duration.ofMinutes(5), 5_000)
        ));
        return cacheManager;
    }

    private CaffeineCache buildCache(String name, Duration ttl, long maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }
}
```

새로운 캐시가 필요하면 `buildCache()` 호출을 하나 추가하면 된다.

#### Facade 메서드 추가 (`ProductFacade.java`)

기존 `getProducts()`, `getProduct()`는 그대로 두고, 로컬 캐시가 적용된 별도 메서드를 추가했다.

```java
// 상품 목록 — 로컬 캐시 (3페이지까지만 캐싱)
@Cacheable(
        value = "productSearch",
        key = "(#condition.keyword() != null ? #condition.keyword() : 'all') + '_' + #condition.sort() + '_' + (#condition.brandId() != null ? #condition.brandId() : 'all') + '_' + #pageable.pageNumber + '_' + #pageable.pageSize",
        condition = "#pageable.pageNumber <= 2"
)
public Page<ProductInfo> getProductsWithLocalCache(ProductSearchCondition condition, Pageable pageable) {
    Page<Product> products = productService.search(condition, pageable);
    List<ProductInfo> content = products.getContent().stream()
            .map(ProductInfo::from)
            .toList();
    return new PageImpl<>(content, pageable, products.getTotalElements());
}

// 상품 상세 — 로컬 캐시
@Cacheable(value = "productDetail", key = "#productId")
public ProductDetailInfo getProductWithLocalCache(Long productId) {
    Product product = productService.findById(productId);
    return ProductDetailInfo.from(product);
}
```

**캐시 키 설계:**
- 상품 목록: `{keyword}_{sort}_{brandId}_{page}_{size}` 조합으로, 동일한 검색 조건 + 페이지네이션이면 같은 캐시를 사용한다.
- 상품 상세: `productId` 단일 키. 상품 ID별로 캐시 엔트리가 생성된다.

**페이지 제한 (`condition = "#pageable.pageNumber <= 2"`):**
- 0, 1, 2페이지(3페이지)까지만 캐시를 적용하고, 4페이지 이후는 캐시를 거치지 않고 매번 DB를 조회한다.
- 대부분의 사용자는 1~2페이지에서 원하는 상품을 찾으므로, 트래픽이 집중되는 앞쪽 페이지만 캐싱하여 Cache Hit율을 높인다.
- 4페이지 이후도 인덱스 최적화(0.2ms)가 되어 있으므로 캐시 없이 충분히 빠르다.
- `condition`이 `false`면 `@Cacheable` AOP가 아예 동작하지 않아, 캐시 조회/저장 모두 스킵된다.

**`Page` → `PageImpl` 변환 이유:**
Spring의 `@Cacheable`은 반환 객체를 캐시에 저장하는데, `Page<Product>`에는 JPA 엔티티가 포함되어 있어
영속성 컨텍스트 밖에서 문제가 될 수 있다. `ProductInfo`(record)로 변환한 `PageImpl`을 저장하여 이를 방지했다.

#### 별도 API 엔드포인트 (`ProductV1Controller.java`)

```java
// 상품 목록 — 로컬 캐시
@GetMapping("/local-cache")
public ApiResponse<Page<ProductV1Dto.ProductResponse>> getProductsWithLocalCache(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) ProductSortType sort,
        @RequestParam(required = false) Long brandId,
        Pageable pageable) { ... }

// 상품 상세 — 로컬 캐시
@GetMapping("/{productId}/local-cache")
public ApiResponse<ProductV1Dto.ProductDetailResponse> getProductWithLocalCache(
        @PathVariable Long productId) { ... }
```

### `@Cacheable` 동작 원리 — Spring AOP 프록시

`@Cacheable`은 Spring AOP 프록시를 통해 동작한다. 메서드 내부에 캐시 조회/저장 코드를 직접 작성하지 않아도 된다.

```
호출 → [AOP 프록시 (CacheInterceptor)]
         ↓
       캐시에 key 존재? ──→ Cache Hit → 캐시된 값 반환 (메서드 실행 안 함)
         ↓ No
       Cache Miss → 실제 메서드 실행 → 반환값을 Cache.put(key, result) → 결과 반환
```

- `CacheInterceptor`가 `@Cacheable`이 붙은 메서드 호출을 가로채서, 캐시 조회 → 미스 시 실행 + 저장을 자동 처리
- 이 메커니즘이 가능한 이유: `@Cacheable`이 붙은 클래스가 **Spring Bean**으로 등록되어 있고, Spring이 빈 생성 시 **프록시 객체**로 감싸기 때문
- 같은 클래스 내부에서 `this.method()`로 호출하면 프록시를 거치지 않아 캐시가 동작하지 않는다 (Self-invocation 주의)

### 비교 테스트 방법

```
# 기존 (캐시 없음) — 매번 DB 쿼리 실행
GET /api/v1/products?sort=LIKE_DESC&page=0&size=20
GET /api/v1/products/{id}

# 로컬 캐시 적용 — 두 번째 호출부터 JVM 메모리에서 반환
GET /api/v1/products/local-cache?sort=LIKE_DESC&page=0&size=20
GET /api/v1/products/{id}/local-cache
```

### E2E 테스트 (`ProductLocalCacheApiE2ETest.java`)

로컬 캐시 적용 API에 대해 8개의 E2E 테스트를 작성했다.

| 대상 | 테스트 케이스 | 검증 포인트 |
|------|------------|-----------|
| 상품 목록 | 기본 조회 → 200 OK | API 정상 동작 |
| 상품 목록 | 동일 조건 2회 호출 → 결과 동일 | Cache Hit 시 동일 데이터 반환 |
| 상품 목록 | 3페이지(page=2) 2회 호출 → 결과 동일 | 캐시 제한 경계값에서 캐시 정상 동작 |
| 상품 목록 | 4페이지(page=3) 호출 → 캐시 미저장 | `condition` 조건에 의해 캐시 스킵 검증 |
| 상품 목록 | 다른 정렬 조건 → 각각 별도 캐시 | 캐시 키 분리 동작 |
| 상품 상세 | 존재하는 상품 조회 → 200 OK | API 정상 동작 + 데이터 정합성 |
| 상품 상세 | 동일 상품 2회 호출 → 결과 동일 | Cache Hit 시 동일 데이터 반환 |
| 상품 상세 | 존재하지 않는 상품 → 404 | 예외 처리 정상 동작 |

테스트 환경:
- `TestRestTemplate`으로 실제 HTTP 요청 전송
- `@AfterEach`에서 DB 초기화 + 캐시 초기화(`cacheManager.getCache().clear()`)
- 인증 없이 접근 가능하도록 `WebMvcConfig`에서 로컬 캐시 경로를 `excludePathPatterns`에 추가

### 구현 중 발생한 이슈와 해결

#### 1. 인증 인터셉터 차단 → 500 응답

로컬 캐시 API 경로(`/local-cache`)가 `MemberAuthInterceptor`의 `excludePathPatterns`에 없어서
인증 없는 요청이 500 에러를 반환했다.

**해결:** `WebMvcConfig`에 아래 경로 추가
```java
.excludePathPatterns(
        // ...기존 경로...
        "/api/v1/products/local-cache",
        "/api/v1/products/{id}/local-cache"
);
```

#### 2. SpEL 표현식 NullPointerException

`@Cacheable`의 key 표현식에서 `keyword`가 null일 때 `T(String).valueOf(null)`이
`String.valueOf(char[])` 오버로드로 해석되어 NPE가 발생했다.

**해결:** null-safe 삼항 연산자로 변경
```java
// Before (NPE 발생)
key = "T(String).valueOf(#condition.keyword()) + '_' + ..."

// After (null-safe)
key = "(#condition.keyword() != null ? #condition.keyword() : 'all') + '_' + ..."
```

`String.valueOf()`가 null을 처리 못하는 이유:
Java의 `String.valueOf()`는 `Object` 버전과 `char[]` 버전이 오버로드되어 있는데,
SpEL에서 `null`을 넘기면 `char[]` 버전이 선택되어 NPE가 발생한다.

### 로컬 캐시의 트레이드오프

| 항목 | 장점 | 단점/한계 |
|------|------|----------|
| **응답 속도** | ~1μs, Redis 대비 100~500배 빠름 | - |
| **네트워크** | 네트워크 통신 불필요, 장애 영향 없음 | - |
| **직렬화** | Java 객체 그대로 저장, 변환 비용 없음 | - |
| **서버 간 공유** | - | 불가. 서버마다 독립 캐시 → 불일치 가능 |
| **메모리** | - | JVM 힙 사용, maximumSize 제어 필수 |
| **서버 재시작** | - | 캐시 전부 소멸 (Cold Start) |
| **무효화** | - | 다른 서버의 캐시를 삭제할 수 없음 |

**다중 서버 환경에서의 문제:**
```
Server 1: 상품 가격 수정 → Server 1 캐시 갱신
Server 2: 상품 목록 조회 → Server 2 캐시에는 이전 데이터 반환
→ TTL 만료될 때까지 서버 간 불일치 발생
```

이 한계 때문에 최종 적용은 Redis 캐시로 진행하되,
Caffeine은 **단일 서버 환경이나 불일치를 허용할 수 있는 데이터**(브랜드 목록 등)에 활용하거나,
**L1(Caffeine) + L2(Redis) 2계층 캐시** 구조로 확장할 수 있다.

---

## 2단계: Redis 캐시 적용

### 왜 Redis 캐시가 필요한가

로컬 캐시(Caffeine)는 단일 JVM 내에서만 유효하다.
다중 서버 환경에서는 서버별로 독립된 캐시를 갖기 때문에, 한 서버에서 데이터가 변경되어도 다른 서버의 캐시는 갱신되지 않는다.
Redis는 모든 서버가 공유하는 외부 캐시 저장소이므로 이 문제를 해결한다.

```
[로컬 캐시]
Server 1 (Caffeine) ←→ DB
Server 2 (Caffeine) ←→ DB    ← 서버 간 캐시 불일치 발생

[Redis 캐시]
Server 1 ──┐
           ├──→ Redis ←→ DB  ← 모든 서버가 동일한 캐시 공유
Server 2 ──┘
```

### 고민한 의사결정과 선택 이유

#### 1. 캐시 대상 범위: 목록 + 상세 모두

| 대상 | 선택 이유 |
|------|----------|
| **상품 목록** | 호출 빈도가 가장 높은 API. 매 요청마다 DB 쿼리(검색 조건 + 정렬 + 페이징)를 실행하면 트래픽 증가 시 DB가 병목. 캐시로 DB 부하를 근본적으로 줄인다 |
| **상품 상세** | 상품 클릭 시마다 DB 조회 + 옵션 목록 로딩이 발생. 단건 조회지만 캐시 히트율이 높아 효과적 |

#### 2. TTL 설정: 목록 3분, 상세 10분

TTL은 **"stale 데이터를 사용자에게 얼마나 보여줘도 괜찮은가"**와 **"DB 부하를 얼마나 줄여야 하는가"** 사이의 균형점이다.
TTL이 짧을수록 정합성은 좋아지지만 히트율이 떨어지고, 길수록 히트율은 좋지만 stale 데이터 노출 시간이 늘어난다.

##### 고민 1: 데이터 변경 빈도 — 어떤 데이터가 얼마나 자주 바뀌는가

| 대상 | 변경 주체 | 변경 빈도 | 핵심 변동 요소 |
|------|----------|----------|---------------|
| **상품 목록** | 사용자 좋아요, 신규 상품 등록 | 상시 발생 | 좋아요순 정렬 순서가 바뀜. 최신순/가격순은 상품 등록·가격 수정이 아니면 변하지 않음 |
| **상품 상세** | 관리자 수정 (가격, 설명, 옵션) | 하루 수십~수백 건 | 개별 상품의 가격, 설명, 재고 등 |

목록은 좋아요 수 변동으로 정렬 순서가 수시로 바뀔 수 있어 짧은 TTL이 적합하다.
상세는 관리자 수정이 드물고, 수정 시 명시적 evict으로 즉시 반영되므로 긴 TTL로 Hit율을 극대화해도 안전하다.

##### 고민 2: stale 데이터의 사용자 영향 — 뭐가 더 위험한가

| 상황 | 사용자 영향 | 심각도 |
|------|-----------|--------|
| 목록 정렬 순서가 약간 다름 | "아까랑 순서가 좀 다른데?" 정도. 인지하기 어려움 | 낮음 |
| 목록에 삭제된 상품이 보임 | 클릭하면 404 → 약간 불쾌하지만 치명적이지 않음 | 낮음 |
| **상세에서 이전 가격이 보임** | **결제 시 가격 차이 → CS 이슈, 신뢰도 하락** | **높음** |
| **상세에서 품절인데 재고 있음으로 보임** | **주문 실패 → 사용자 이탈** | **높음** |

목록의 stale 데이터는 사용자가 인지하기 어렵고 치명적이지 않지만,
상세의 stale 데이터(가격, 재고)는 직접적인 CS 이슈로 이어질 수 있다.

##### 고민 3: 그러면 상세 TTL이 오히려 더 짧아야 하는 거 아닌가?

stale 데이터의 위험도만 보면 상세 TTL이 더 짧아야 할 것 같지만,
**상세에는 명시적 evict이 있다는 점**이 핵심 차이다.

```
[상품 상세 — 정상 케이스]
관리자 수정 → AdminProductFacade에서 evictProductDetail(productId) 호출
→ Redis에서 해당 키 즉시 삭제 → 다음 요청 시 DB에서 최신 데이터 조회
→ TTL과 무관하게 즉시 반영
```

상세의 TTL은 evict의 **안전망** 역할이다. evict이 실패하는 비정상 케이스(Redis 장애, 코드 버그 등)에서
TTL이 없으면 stale 데이터가 영원히 남을 수 있다. TTL 10분은 "최악의 경우에도 10분 후에는 반드시 갱신된다"는 보장이다.

반면 **목록은 evict이 없다.** 키 조합이 keyword × sort × brandId × page × size로 너무 많아 명시적 삭제가 비현실적이다.
TTL 만료가 유일한 갱신 수단이므로, TTL 자체가 "최대 stale 허용 시간"이 된다.

##### 결론: 무효화 전략에 따른 TTL 차등 설계

| 대상 | TTL | 무효화 전략 | TTL의 역할 | 선택 이유 |
|------|-----|-----------|-----------|----------|
| **상품 목록** | **3분** | TTL 만료 의존 | **유일한 갱신 수단** | evict 불가 → TTL이 곧 최대 stale 허용 시간. 좋아요순 정렬 변동을 고려하되, 3분 지연은 사용자 경험에 큰 영향을 주지 않는 수준 |
| **상품 상세** | **10분** | TTL + **명시적 evict** | **evict 실패 시 안전망** | 정상 케이스에서는 evict이 즉시 반영하므로 TTL이 길어도 안전. 긴 TTL로 Hit율을 극대화하면서, 최악의 경우에도 10분 후 갱신 보장 |

정리하면:
- **목록 3분**: TTL이 유일한 갱신 수단이므로 짧게. 하지만 너무 짧으면(1분 미만) Hit율이 급격히 떨어져 캐시 효과가 반감됨.
- **상세 10분**: evict이 정상 동작하면 TTL에 도달할 일이 거의 없음. 안전망이므로 길게 잡아 Hit율을 극대화.

#### 3. 캐시 무효화 전략: 상세는 명시적 삭제, 목록은 TTL 의존

두 가지 전략의 트레이드오프를 비교한 후, 데이터 특성에 맞게 혼합 적용했다.

**TTL 만료 의존:**
```
상품 수정 → 아무것도 안 함 → TTL 지나면 자연 만료 → 다음 요청 시 DB 조회 후 캐시 갱신
```
- 장점: 구현 단순, 캐시 삭제 로직 불필요, 버그 가능성 낮음
- 단점: TTL 동안 옛날 데이터 노출 (가격 변경 후 3분간 이전 가격이 보일 수 있음)

**명시적 삭제 (Cache Eviction):**
```
상품 수정 → Redis에서 해당 키 삭제 → 다음 요청 시 DB 조회 후 캐시 갱신
```
- 장점: 변경 즉시 최신 데이터 반영
- 단점: 삭제 대상 키를 정확히 알아야 함, 목록처럼 키 조합이 많으면 전체 삭제가 어려움

| 대상 | 전략 | 선택 이유 |
|------|------|----------|
| **상품 상세** | TTL + **명시적 삭제** | 캐시 키가 `product:detail:{id}` 하나라 삭제가 단순. 가격/설명 등 수정 시 사용자에게 즉시 반영되어야 하므로 명시적 삭제가 필수 |
| **상품 목록** | **TTL 만료 의존** | 캐시 키가 keyword × sort × brandId × page × size 조합이라 명시적 삭제가 비현실적. `KEYS` 명령은 O(N)으로 운영 환경에서 위험하고, 1~3분 지연은 보통 허용 가능 |

#### 4. 직렬화 방식: Jackson JSON

| 방식 | 장단점 |
|------|--------|
| **Jackson JSON (선택)** | Redis CLI로 값 확인 가능, 디버깅 용이. `ProductInfo`, `ProductDetailInfo`가 record 타입이라 Jackson 기본 지원 |
| GenericJackson2Json | 타입 정보(`@class`)를 같이 저장하여 편리하지만 보안 이슈(역직렬화 공격) 가능 |
| JDK Serialization | 바이너리라 읽을 수 없고, 클래스 변경 시 깨짐. 레거시 방식 |

현재 `RedisTemplate<String, String>`에 `StringRedisSerializer`가 설정되어 있으므로,
`ObjectMapper`로 직접 JSON 변환 후 String으로 저장/조회하는 방식을 택했다.
이 방식은 직렬화 로직을 명시적으로 제어할 수 있어 디버깅과 유지보수에 유리하다.

#### 5. 캐시 구조: Redis 단독 (Caffeine + Redis 2-Level 아님)

| 구조 | 장단점 |
|------|--------|
| **Redis 단독 (선택)** | 구현 단순. 모든 서버가 동일한 캐시를 공유하여 일관성 보장. 네트워크 비용(~1ms)은 DB 조회 대비 무시할 수 있는 수준 |
| Caffeine → Redis 2-Level | 최고 성능이지만, 구현 복잡도가 높고 L1 무효화 동기화(Pub/Sub 등)가 필요. 현재 규모에서는 오버엔지니어링 |
| Caffeine만 유지 | 네트워크 비용 없지만, 다중 서버 환경에서 캐시 불일치 문제 해결 불가 |

#### 6. 적용 레이어: Facade

```
Controller → Facade → Service → Repository → DB
                ↑
          캐시 적용 위치 (로컬 캐시와 동일)
```

| 레이어 | 장단점 |
|--------|--------|
| **Facade (선택)** | Info DTO를 캐싱하므로 DB 조회 + Entity→DTO 변환을 모두 스킵. 도메인 로직(Service)과 캐시 로직이 분리됨 |
| Service | Entity 캐싱. JPA 영속성 컨텍스트 밖에서 문제 가능. DTO 변환 비용 절약 못 함 |
| Repository | 쿼리 결과 캐싱. 세밀한 제어 가능하지만 DTO 변환 비용 절약 못 함 |

로컬 캐시(Caffeine)도 Facade에 적용했으므로, Redis 캐시도 같은 레이어에 적용하여 일관성을 유지했다.

#### 7. 장애 대응: try-catch fallback

Redis는 캐시(보조 저장소)이므로, Redis 장애가 서비스 장애로 전파되면 안 된다.

```java
// ProductRedisCache.java
public Optional<ProductDetailInfo> getProductDetail(Long productId) {
    try {
        String json = redisTemplate.opsForValue().get(detailKey(productId));
        if (json == null) return Optional.empty();
        return Optional.of(objectMapper.readValue(json, ProductDetailInfo.class));
    } catch (Exception e) {
        log.warn("Redis 상품 상세 조회 실패: productId={}", productId, e);
        return Optional.empty(); // Redis 장애 시 DB fallback
    }
}
```

- 모든 Redis 조회/저장/삭제 메서드를 try-catch로 감싸서, 예외 발생 시 로그만 남기고 DB에서 직접 조회하도록 처리
- Spring의 `CacheErrorHandler` 대안도 있으나, `@Cacheable` 기반이 아닌 직접 캐시 관리 방식을 사용하므로 try-catch가 더 명시적이고 제어가 쉬움

#### 8. 목록 캐시 페이지 제한: 3페이지(page 0~2)까지만 캐싱

| 결정 | 이유 |
|------|------|
| **3페이지 제한** | 대부분의 사용자 트래픽은 첫 1~3페이지에 집중된다. 깊은 페이지까지 캐싱하면 키 수가 폭발적으로 늘어나 Redis 메모리를 낭비하고 히트율이 떨어진다 |

```java
// ProductRedisCache.java
private static final int MAX_CACHEABLE_PAGE = 2; // 0, 1, 2

public boolean isCacheablePage(int pageNumber) {
    return pageNumber <= MAX_CACHEABLE_PAGE;
}
```

3페이지 초과 요청은 캐시를 거치지 않고 DB에서 직접 조회한다.

### 캐시 키 설계

| 대상 | 키 패턴 | 예시 |
|------|--------|------|
| 상품 상세 | `product:detail:{productId}` | `product:detail:123` |
| 상품 목록 | `product:search:{keyword}:{sort}:{brandId}:{page}:{size}` | `product:search:all:LATEST:all:0:20` |

- null 값은 `"all"`로 치환하여 키 충돌 방지
- 목록 키는 검색 조건의 모든 파라미터를 포함하여 동일한 요청에만 캐시 히트

### 구현 구조

기존 `getProducts()`, `getProduct()` 메서드에 Redis 캐시를 직접 적용했다.
별도 엔드포인트를 만들지 않고, 기존 API가 그대로 캐시 혜택을 받는다.

```
ProductV1Controller
  └─ GET /api/v1/products → ProductFacade.getProducts()
  └─ GET /api/v1/products/{id} → ProductFacade.getProduct()
                              │
                              ▼
                        ProductRedisCache (try-catch fallback)
                          ├─ Cache Hit → 캐시된 데이터 반환
                          └─ Cache Miss → ProductService.search/findById()
                                            → DB 조회 → Redis 저장 → 반환

AdminProductFacade
  └─ updateProduct() → 수정 후 → productRedisCache.evictProductDetail(productId)
  └─ deleteProduct() → 삭제 후 → productRedisCache.evictProductDetail(productId)
```

### E2E 테스트 (`ProductRedisCacheApiE2ETest.java`)

기존 API 엔드포인트에 대해 7개의 E2E 테스트를 작성했다.

| 대상 | 테스트 케이스 | 검증 포인트 |
|------|------------|-----------|
| 상품 목록 | 조회 시 Redis 캐시 키 생성 | 캐시 저장 동작 |
| 상품 목록 | 동일 조건 2회 호출 → 결과 동일 | Cache Hit 시 동일 데이터 반환 |
| 상품 목록 | 다른 정렬 조건 → Redis 키 2개 생성 | 캐시 키 분리 동작 |
| 상품 목록 | page=3 조회 → Redis 키 없음 | 3페이지 초과 캐싱 제한 |
| 상품 상세 | 조회 시 Redis 캐시 키 생성 | 캐시 저장 + 데이터 정합성 |
| 상품 상세 | 동일 상품 2회 호출 → 결과 동일 | Cache Hit 시 동일 데이터 반환 |
| 상품 상세 | 존재하지 않는 상품 → 404 + 캐시 없음 | 예외 시 캐시 미저장 |

테스트 환경:
- `TestRestTemplate`으로 실제 HTTP 요청 전송
- `@AfterEach`에서 DB 초기화(`DatabaseCleanUp`) + Redis 초기화(`RedisCleanUp.truncateAll()`)
- Testcontainers로 Redis 컨테이너 자동 실행

### 로컬 캐시 vs Redis 캐시 비교

| 항목 | 로컬 캐시 (Caffeine) | Redis 캐시 |
|------|--------------------|-----------  |
| **응답 속도** | ~1μs (메모리 직접 접근) | ~1ms (네트워크 통신) |
| **직렬화** | 불필요 (Java 객체 그대로) | JSON 직렬화/역직렬화 필요 |
| **서버 간 공유** | 불가 (서버별 독립 캐시) | **가능 (모든 서버가 공유)** |
| **무효화** | 해당 서버만 적용 | **전체 서버에 즉시 적용** |
| **장애 영향** | 없음 (JVM 내부) | Redis 다운 시 fallback 필요 |
| **메모리** | JVM 힙 사용 | Redis 서버 메모리 사용 |
| **서버 재시작** | 캐시 소멸 | **캐시 유지** |

단일 서버에서는 로컬 캐시가 빠르지만, 다중 서버 환경에서는 Redis 캐시가 일관성 면에서 필수적이다.

---

## 3단계: 성능 비교 테스트 — Redis Cache vs Local Cache (Caffeine)

### 테스트 목적

이론적인 트레이드오프 비교를 넘어, **실제 E2E 환경에서 정량적인 수치**로 두 캐시 전략을 비교한다.
단순히 "어느 쪽이 빠른가"가 아니라, **각 전략을 선택했을 때 감수해야 하는 비용**을 측정하여 최종 선택의 근거로 삼는다.

### 테스트 환경

- 데이터: 브랜드 5개, 상품 50개, 옵션 각 1개
- HTTP 호출: `TestRestTemplate` (실제 Tomcat + Spring MVC 전체 스택 경유)
- 인프라: Testcontainers (MySQL 8.0 + Redis 7.0)
- 측정 방식: `System.nanoTime()` 기반, 50회 반복 후 평균/p50/p95/p99 산출
- Warm-up: 각 시나리오 시작 전 1회 warm-up 호출로 JIT 컴파일 영향 최소화

### 측정 지표 선택 이유

| 지표 | 왜 측정하는가 |
|------|-------------|
| **평균 응답시간** | 전반적인 성능 수준 파악. 단, 극단값에 영향을 받을 수 있다 |
| **p50 (중앙값)** | "일반적인 사용자"가 체감하는 응답시간. 평균보다 실질적 체감 지표 |
| **p95 / p99** | 상위 5%, 1% 느린 요청의 응답시간. SLA 기준이 되는 꼬리 지연(tail latency). p99가 높으면 일부 사용자가 불쾌한 경험을 한다 |
| **Cache Miss 시간** | 첫 요청(= 캐시 워밍 비용)의 크기. 캐시 만료 직후 사용자가 겪는 최악의 응답시간 |
| **Evict 후 재조회** | 관리자 상품 수정 → 캐시 삭제 → 사용자 재조회 시나리오의 실제 비용 |
| **다양한 조건 Miss→Hit 전환** | 실사용에서 검색 조건이 다양할 때 캐시 워밍업이 얼마나 빨리 일어나는가 |

### 테스트 시나리오와 결과

#### 시나리오 1: 상품 목록 조회 — 기준선 vs Cache Hit 비교

**왜 이 테스트를 하는가:**
캐시의 가장 기본적인 효과(DB 쿼리 제거)를 정량적으로 확인한다.
No Cache(DB 직접)와 Cache Hit 간 차이가 캐시 도입의 정당성을 증명한다.

| 구분 | 평균 | p50 | p95 | p99 |
|------|------|-----|-----|-----|
| **No Cache (DB 직접, page=3)** | 12.37ms | 11.98ms | 18.31ms | 29.18ms |
| **Redis Cache Hit** | 5.83ms | 5.32ms | 8.25ms | 18.24ms |
| **Local Cache Hit** | **1.39ms** | **1.32ms** | **1.84ms** | **2.46ms** |

**분석:**
- DB 직접 조회 대비 Redis는 **약 2.1배**, Local은 **약 8.9배** 빠르다.
- p99 기준으로 보면 차이가 더 극적이다: DB 29.18ms → Redis 18.24ms → Local 2.46ms.
- Local Cache가 빠른 이유: 네트워크 통신 없이 JVM 힙에서 Java 객체를 바로 반환하므로 직렬화/역직렬화 비용도 없다.
- Redis는 네트워크 왕복(~1ms) + JSON 역직렬화 오버헤드가 존재하지만, 그래도 DB 조회 대비 절반 수준으로 의미 있는 개선이다.

#### 시나리오 2: Cache Miss — 첫 요청 비용

**왜 이 테스트를 하는가:**
캐시가 비어 있을 때(서버 재시작, TTL 만료 직후) 사용자가 겪는 최악의 응답시간을 파악한다.
Cache Miss가 너무 비싸면 TTL 전략이나 캐시 워밍업 정책을 재고해야 한다.

| 구분 | Cache Miss 응답시간 |
|------|-------------------|
| **Redis** | 39.86ms |
| **Local** | 16.37ms |

**분석:**
- Redis Cache Miss가 Local보다 약 2.4배 느리다.
- Redis Miss 비용 = DB 조회 + Entity→DTO 변환 + JSON 직렬화 + Redis SET 네트워크 호출.
- Local Miss 비용 = DB 조회 + Entity→DTO 변환 + JVM 메모리 저장(무시할 수 있는 수준).
- Redis의 Miss 비용(39.86ms)은 No Cache(12.37ms)보다 오히려 높다. 이는 캐시 저장(Redis SET) 비용이 추가되기 때문이다. 하지만 이후 반복 호출은 5.83ms로 빠르므로, 첫 요청의 추가 비용은 후속 Cache Hit으로 충분히 상쇄된다.

#### 시나리오 3: 상품 상세 조회 — Cache Hit 비교

**왜 이 테스트를 하는가:**
상세 조회는 목록과 달리 단건 조회이고, 옵션 목록 로딩이 추가된다. 데이터 구조가 다른 API에서도 캐시 효과가 일관되는지 확인한다.

| 구분 | 평균 | p50 | p95 | p99 |
|------|------|-----|-----|-----|
| **No Cache (DB 직접)** | 9.42ms | 9.06ms | 12.59ms | 15.57ms |
| **Redis Cache Hit** | 3.77ms | 3.68ms | 4.46ms | 5.57ms |
| **Local Cache Hit** | **0.87ms** | **0.80ms** | **1.25ms** | **2.67ms** |

**분석:**
- 목록과 동일한 패턴: Local > Redis > No Cache 순으로 빠르다.
- 상세 조회는 목록 대비 데이터 크기가 작아(단건 + 옵션 몇 개) 전반적으로 응답이 빠르다.
- Redis Cache Hit(3.77ms)과 Local Cache Hit(0.87ms)의 차이(약 2.9ms)는 대부분 네트워크 왕복 + JSON 역직렬화 시간이다.

#### 시나리오 4: 캐시 Evict 후 재조회 — 무효화 비용

**왜 이 테스트를 하는가:**
관리자가 상품을 수정하면 캐시를 삭제(evict)하고, 다음 사용자 요청이 DB에서 새로 조회한다.
이 "수정 → 삭제 → 재조회" 사이클이 얼마나 자주, 빠르게 일어나는지가 운영 비용에 직결된다.

| 구분 | 평균 | p50 | p95 | p99 |
|------|------|-----|-----|-----|
| **Redis** evict → 재조회 | 9.28ms | 8.97ms | 12.94ms | 14.30ms |
| **Local** evict → 재조회 | 7.69ms | 7.73ms | 10.28ms | 10.97ms |

**분석:**
- 둘 다 No Cache 직접 조회(9.42ms)와 비슷한 수준이다. evict 후에는 결국 DB를 조회하기 때문.
- Redis의 evict 비용이 약 1.6ms 더 높다: `DEL` 명령의 네트워크 왕복 + 재조회 시 Redis SET 비용이 포함되기 때문.
- 이 차이는 관리자 수정 빈도가 낮은 현재 상황에서 무시할 수 있는 수준이다. 상품 수정은 하루 수십~수백 건이지만, 조회는 수만~수십만 건이므로 evict 비용보다 Cache Hit 성능이 더 중요하다.

#### 시나리오 5: 다양한 검색 조건 — Miss에서 Hit으로의 전환

**왜 이 테스트를 하는가:**
실제 서비스에서는 단일 조건만 반복되지 않는다. 정렬 조건(LATEST, LIKE_DESC, PRICE_ASC), 페이지, 사이즈가 다양하게 조합된다.
5가지 서로 다른 조건으로 순회하며, 모든 조건이 캐시에 채워진 후 두 번째 순회에서의 성능 향상을 측정한다.

| 구분 | Cache Miss 평균 | Cache Hit 평균 | 감소율 |
|------|---------------|---------------|--------|
| **Redis** | 9.26ms | 3.86ms | **58.3%** |
| **Local** | 7.62ms | 1.20ms | **84.2%** |

**분석:**
- Local Cache가 Hit 시 84.2% 감소로 Redis(58.3%)보다 효과가 크다.
- 하지만 Redis도 58.3% 감소로, DB 부하를 절반 이상 줄이는 의미 있는 효과를 보인다.
- 실제 운영에서는 동일 조건의 반복 요청이 많으므로(인기 정렬 + 1페이지 등), 실제 Hit율은 이 테스트보다 높을 것으로 예상된다.

### 각 전략 선택 시의 트레이드오프

#### Local Cache (Caffeine) 선택 시

**얻는 것:**
- Cache Hit 응답시간 ~1ms (Redis 대비 약 4배 빠름)
- 네트워크 장애에 영향 받지 않음 (JVM 내부)
- 직렬화 비용 없음 (Java 객체 그대로 저장)
- Redis 인프라 운영 비용 없음

**감수해야 하는 것:**
- **서버 간 캐시 불일치**: 2대 이상의 서버를 운영하면 Server A에서 상품을 수정해도 Server B의 캐시에는 TTL 만료 전까지 이전 데이터가 남는다. 가격 변경 시 서버마다 다른 가격을 보여주는 상황이 발생할 수 있다.
- **캐시 무효화 불가**: 특정 서버의 캐시만 삭제할 수 있고, 다른 서버의 캐시를 원격으로 삭제하려면 별도 메시징(Redis Pub/Sub 등)이 필요하다. 이는 결국 Redis 의존을 만든다.
- **서버 재시작 시 Cold Start**: 배포할 때마다 모든 캐시가 소멸되어 배포 직후 DB에 순간적으로 부하가 몰린다.
- **JVM 힙 메모리 사용**: 캐시 데이터가 커지면 GC에 영향을 줄 수 있다. `maximumSize` 제한이 있지만, 캐시 데이터가 커지면 Old Generation으로 이동하여 Full GC를 유발할 수 있다.

#### Redis Cache 선택 시

**얻는 것:**
- **서버 간 캐시 일관성**: 모든 서버가 같은 Redis를 바라보므로, 한 곳에서 evict하면 전체에 즉시 반영된다.
- **명시적 캐시 무효화**: 관리자 상품 수정 시 `DEL product:detail:{id}` 한 번이면 모든 서버에서 최신 데이터를 조회한다.
- **서버 재시작에도 캐시 유지**: 배포 시 Cold Start 없이 기존 캐시를 계속 활용할 수 있다.
- **스케일아웃 대응**: 서버를 몇 대로 늘리든 Redis 하나로 캐시를 공유한다.

**감수해야 하는 것:**
- **Cache Hit 응답시간 ~4-6ms**: Local(~1ms) 대비 약 4배 느리다. 네트워크 왕복 + JSON 직렬화/역직렬화 비용.
- **Cache Miss 비용 증가**: 첫 요청 시 DB 조회 + Redis SET 비용이 합산되어 No Cache보다 오히려 느릴 수 있다 (39.86ms vs 12.37ms).
- **Redis 인프라 의존**: Redis 자체의 가용성이 서비스에 영향을 줄 수 있다. (다만 try-catch fallback으로 DB 조회로 전환되므로 서비스 중단은 아님)
- **직렬화 오버헤드**: 매 캐시 조회/저장마다 JSON 변환이 필요하다.

### 최종 선택: Redis Cache

성능 수치만 보면 Local Cache가 압도적으로 빠르지만, **최종적으로 Redis Cache를 선택**했다.

#### 선택 근거

**1. 다중 서버 환경에서의 정합성이 성능보다 중요하다**

현재는 단일 서버지만, 서비스가 성장하면 최소 2대 이상의 서버를 운영해야 한다.
이때 Local Cache는 서버 간 캐시 불일치가 구조적으로 발생하며, 이는 사용자 경험에 직접적인 영향을 준다.

```
[Local Cache — 서버 2대 운영 시]
1. 관리자가 Server A를 통해 상품 가격을 10,000원 → 8,000원으로 수정
2. Server A의 캐시는 갱신됨 (또는 evict 후 다음 요청 시 갱신)
3. Server B의 캐시에는 TTL 만료 전까지 10,000원이 남아 있음
4. 로드밸런서가 요청을 Server B로 보내면 → 사용자는 이전 가격(10,000원)을 본다
→ 최대 TTL(3~5분) 동안 서버마다 다른 가격을 보여주는 문제 발생

[Redis Cache — 서버 2대 운영 시]
1. 관리자가 상품 가격 수정 → Redis에서 해당 캐시 키 삭제
2. Server A든 Server B든 다음 요청 시 Redis Cache Miss → DB에서 최신 가격 조회
→ 모든 서버에서 즉시 동일한 최신 데이터를 반환
```

**2. Redis의 응답시간(~4-6ms)도 충분히 빠르다**

DB 직접 조회(~10-12ms) 대비 Redis Cache Hit(~4-6ms)은 50% 이상 개선이다.
사용자 입장에서 4ms와 1ms의 차이는 체감하기 어렵지만, 서버 간 가격 불일치는 즉시 인지할 수 있다.
**사용자가 느끼지 못하는 3ms를 줄이는 것보다, 사용자가 바로 알아채는 데이터 불일치를 방지하는 것이 우선이다.**

**3. 캐시 무효화 전략이 실용적이다**

- 상품 상세: `DEL product:detail:{id}` 한 번으로 전체 서버에 즉시 반영.
- 상품 목록: TTL 3분 만료에 의존하되, 키 조합이 복잡하여 명시적 삭제가 비현실적인 점은 Local과 동일.
- Local Cache에서 다른 서버의 캐시를 무효화하려면 Redis Pub/Sub 같은 별도 메시징 인프라가 필요한데, 그러면 결국 Redis를 써야 한다.

**4. 운영 안정성 — 배포 시 Cold Start 방지**

Local Cache는 서버 재시작 시 모든 캐시가 소멸된다.
롤링 배포 중에는 새로 뜬 서버에 요청이 몰리면서 순간적으로 모든 요청이 DB를 직접 조회하게 된다(Cache Stampede).
Redis Cache는 서버가 재시작되어도 캐시가 유지되므로 배포 직후에도 Cache Hit율이 유지된다.

**5. 성능이 정말 중요해지면 2-Level Cache로 확장 가능하다**

현재 규모에서는 Redis 단독으로 충분하지만, 트래픽이 극단적으로 늘어나면
L1(Caffeine) → L2(Redis) → DB 구조로 확장할 수 있다.
Redis를 기반으로 두고 Local Cache를 앞단에 추가하는 것은 자연스러운 확장이지만,
Local Cache만으로 시작한 뒤 Redis를 나중에 추가하는 것은 캐시 무효화 설계를 처음부터 다시 해야 한다.

#### 결론 요약

| 판단 기준 | Local Cache | Redis Cache | 비중 |
|----------|-------------|-------------|------|
| Cache Hit 속도 | **1.39ms** | 5.83ms | 낮음 — 사용자 체감 차이 미미 |
| 서버 간 정합성 | 불일치 발생 | **일관성 보장** | **높음** — 가격/재고 불일치는 CS 이슈 |
| 캐시 무효화 | 해당 서버만 | **전체 서버** | **높음** — 관리자 수정 즉시 반영 |
| 배포 안정성 | Cold Start | **캐시 유지** | 중간 — 배포 빈도에 따라 영향 |
| 인프라 복잡도 | 없음 | Redis 운영 필요 | 낮음 — 이미 Redis 인프라 존재 |
| 확장성 | 서버별 독립 | **공유 캐시** | **높음** — 스케일아웃 필수 |

**"속도 4배 차이"보다 "데이터 일관성"과 "운영 안정성"의 가치가 더 크다.**
Redis Cache를 기본 전략으로 채택하고, 필요 시 L1 Local Cache를 앞단에 추가하는 방향으로 확장한다.

---

## Cache Miss 추가 비용 검증 — JSON 직렬화 + Redis SET은 얼마나 걸리는가

### 문제 제기

Redis Cache 선택 시 감수해야 할 점으로 "Cache Miss 비용 증가"를 언급했다.
Cache Miss 시 DB 조회에 더해 **JSON 직렬화 + Redis SET** 비용이 추가되어, 캐시가 없을 때보다 오히려 느려지는 구간이 존재한다.

그렇다면 이 추가 비용이 **실제로 얼마나 되는지**, 감으로 판단하지 않고 수치로 증명한다.

### 측정 방법

`RedisCacheOperationCostTest`에서 각 연산을 **격리하여 순수 비용만 측정**한다.
JPA 엔티티가 아닌 `restoreFromCache()`로 생성한 순수 도메인 객체를 사용하여, Lazy Loading 등 JPA 관련 오버헤드를 배제한다.

- JIT 컴파일 안정화를 위해 50회 warm-up 후 100회 반복 측정
- 데이터: 상품 50건, 옵션 각 2개 (실제 운영과 유사한 크기)
- 환경: Testcontainers Redis (로컬)

### 측정 결과

#### 단건 상품 기준

| 연산 | 평균 | p50 | p95 |
|------|------|-----|-----|
| JSON 직렬화 (`writeValueAsString`) | **0.04ms** (42μs) | 32μs | 70μs |
| JSON 역직렬화 (`readValue`) | **0.20ms** (198μs) | 86μs | 311μs |
| Redis SET | **0.44ms** (438μs) | 435μs | 526μs |
| Redis GET | **0.43ms** (428μs) | 423μs | 501μs |

#### 목록 10건 기준

| 연산 | 평균 | p50 | p95 |
|------|------|-----|-----|
| JSON 직렬화 | **0.05ms** (50μs) | 43μs | 95μs |
| JSON 역직렬화 | **0.11ms** (111μs) | 107μs | 176μs |
| Redis SET | **0.42ms** (418μs) | 392μs | 485μs |
| Redis GET | **0.40ms** (401μs) | 374μs | 567μs |

#### 합산 비용 (Cache Miss/Hit 시 추가되는 시간)

| 시나리오 | 평균 | p50 | p95 |
|----------|------|-----|-----|
| **Cache Miss 추가 비용** (직렬화 + SET) | **0.36ms** | 347μs | 427μs |
| **Cache Hit 전체 비용** (GET + 역직렬화) | **0.37ms** | 365μs | 441μs |

#### JSON 페이로드 크기

| 대상 | 크기 |
|------|------|
| 단건 상품 (옵션 2개) | 447 bytes (0.4 KB) |
| 목록 10건 (옵션 각 2개) | 4,526 bytes (4.4 KB) |
| 목록 20건 (옵션 각 2개) | 9,096 bytes (8.9 KB) |

### 분석

**1. JSON 직렬화 비용은 사실상 무시할 수 있다**

단건 0.04ms, 10건 0.05ms. 데이터 크기가 10배로 늘어나도 직렬화 시간은 거의 동일하다.
Jackson ObjectMapper는 JIT 컴파일 후 안정화되면 μs 단위로 동작한다.

**2. Redis SET/GET이 전체 비용의 대부분을 차지한다**

직렬화 0.04ms vs Redis SET 0.44ms. 추가 비용의 **90% 이상이 Redis 네트워크 왕복**이다.
직렬화 최적화(MessagePack 등)를 고려하는 것보다 네트워크 레이턴시를 줄이는 것(Redis를 같은 VPC에 배치 등)이 더 효과적이다.

**3. Cache Miss 추가 비용은 DB 조회 대비 미미하다**

```
DB 조회 시간:       ~10-12ms (성능 비교 테스트 기준)
Cache Miss 추가 비용: ~0.36ms (직렬화 + Redis SET)
→ DB 조회 대비 약 3% 수준
```

Cache Miss 시 총 비용은 `DB 조회(10ms) + 추가 비용(0.36ms) ≈ 10.36ms`로,
캐시 없이 DB만 조회하는 10ms와 체감 차이가 거의 없다.

**4. 이 추가 비용은 이후 Cache Hit으로 즉시 회수된다**

```
Cache Miss 1회 투자: 0.36ms
Cache Hit 1회 절약: 10ms - 0.37ms = 9.63ms
→ TTL(10분) 동안 2회 이상 조회되면 이미 이득
→ 100회 조회 시: 0.36ms 투자로 963ms 절약 (ROI 2,675배)
```

### 결론

"Cache Miss 시 오히려 느려진다"는 우려는 **수치적으로 근거가 약하다**.
추가 비용 0.36ms는 DB 조회 10ms 대비 3%에 불과하며, 2회 이상의 Cache Hit으로 즉시 상쇄된다.
실무에서 중요한 것은 이런 추가 비용을 걱정하는 것이 아니라, **Cache Stampede 방지(jitter, 캐시 워밍)**에 집중하는 것이다.

---

## 캐시 웜업 (Cache Warm-Up)

### 문제 인식

현재 Cache-Aside 패턴은 첫 번째 요청이 항상 Cache Miss다.
서버 재시작(배포)이나 Redis 장애 복구 직후, 동시에 많은 요청이 DB로 직행하는 **Cache Stampede(Thundering Herd)** 문제가 발생할 수 있다.

```
[웜업 없음] 서버 재시작 → 캐시 비어있음 → 첫 요청들 전부 DB 직행 → DB 부하 급증
[웜업 적용] 서버 재시작 → 웜업으로 캐시 채움 → 첫 요청부터 Cache Hit → DB 부하 없음
```

### 웜업 대상 선정 기준

모든 데이터를 웜업하면 오히려 기동 시 DB 부하가 커지므로, **트래픽이 집중되는 최소 범위**만 대상으로 한다.

| 대상 | 선정 이유 |
|------|----------|
| 상품 목록 0~2페이지 (기본 정렬) | `MAX_CACHEABLE_PAGE = 2`로 이미 캐싱 대상이고, 사용자 트래픽이 첫 1~3페이지에 집중 |
| 상품 상세 (첫 페이지 노출 상품) | 목록에 노출된 상품이 클릭될 확률이 높으므로, 첫 페이지 상품의 상세를 함께 웜업 |

**제외한 것:**
- 모든 정렬 조건(LATEST, PRICE_ASC, LIKE_DESC) 조합 → 키워드 × 정렬 × 브랜드 조합이 많아 DB 부하 역효과
- 전체 상품 상세 → 상품 수에 비례하여 기동 시간이 길어짐

### 구현 방식: `@PostConstruct` vs `ApplicationReadyEvent`

| 항목 | `@PostConstruct` | `ApplicationReadyEvent` |
|------|-----------------|------------------------|
| 실행 시점 | Bean 생성 직후 | 모든 초기화 완료 후 |
| DB/Redis 준비 | 보장되지 않음 | **보장됨** |
| 트랜잭션 사용 | 제한적 | **정상 사용 가능** |

→ `ApplicationReadyEvent`를 선택. 모든 인프라가 준비된 후 안전하게 웜업 실행.

### 설계 원칙

1. **웜업 실패가 서비스 기동을 막지 않는다** — 각 항목마다 try-catch로 감싸서, 실패해도 다음 항목으로 진행
2. **기존 `ProductCacheStore` 인터페이스를 그대로 사용** — 웜업도 동일한 캐시 저장소를 통해 적재하므로, 캐시 기술 변경 시 웜업 코드 수정 불필요
3. **웜업 범위는 최소한으로** — 기본 정렬(LATEST) + 필터 없음 조건만 웜업. 확장이 필요하면 조건을 추가

### 구현 위치

`ProductCacheWarmUp.java` (Application Layer)
- `ApplicationReadyEvent` 수신 시 `warmUp()` 실행
- `warmUpSearchCache()`: 기본 정렬 0~2페이지 캐싱
- `warmUpDetailCache()`: 첫 페이지 상품들의 상세 정보 캐싱

---

## 단위 테스트 보완

### 상품 검색 통합 테스트 (`ProductSearchIntegrationTest.java`)

기존에 Q1(브랜드+좋아요순), Q2(브랜드+최신순), Q3(전체+좋아요순), Q6(브랜드+COUNT) 테스트만 존재했으나,
Q4(전체+최신순), Q5(전체+가격순) 테스트를 추가하여 모든 정렬 조건 × 브랜드 필터 유무 조합을 커버했다.

| 쿼리 | 테스트 케이스 | 검증 포인트 | 상태 |
|------|------------|-----------|------|
| Q1 | `searchByBrand_sortByLikeDesc` | 브랜드 필터 + 좋아요 내림차순 정렬 | 기존 |
| Q2 | `searchByBrand_sortByLatest` | 브랜드 필터 + 최신순 정렬 | 기존 |
| Q3 | `searchAll_sortByLikeDesc` | 전체 목록 + 좋아요 내림차순 정렬 | 기존 |
| Q4 | `searchAll_sortByLatest` | 전체 목록 + 최신순 정렬 | **추가** |
| Q5 | `searchAll_sortByPriceAsc` | 전체 목록 + 가격 오름차순 정렬 | **추가** |
| Q6 | `countByBrand` | 브랜드 필터 + 전체 건수 | 기존 |

### ProductFacade 단위 테스트 (`ProductFacadeTest.java`)

기존에는 DB 조회 → DTO 변환만 검증했으나, Redis Cache-Aside 패턴의 분기 로직을 검증하는 테스트를 추가했다.

| 대상 | 테스트 케이스 | 검증 포인트 | 상태 |
|------|------------|-----------|------|
| 상품 목록 | `returnsProductInfoPage_andCachesOnMiss` | Cache Miss → DB 조회 → Redis 저장 | **추가** |
| 상품 목록 | `returnsCachedResult_whenCacheHit` | Cache Hit → DB 미호출, 캐시 데이터 반환 | **추가** |
| 상품 목록 | `queriesDbDirectly_whenPageExceedsCacheLimit` | 4페이지 이상 → 캐시 미사용, DB 직접 조회 | **추가** |
| 상품 상세 | `returnsProductDetail_andCachesOnMiss` | Cache Miss → DB 조회 → Redis 저장 | **추가** |
| 상품 상세 | `returnsCachedResult_whenCacheHit` | Cache Hit → DB 미호출, 캐시 데이터 반환 | **추가** |
| 좋아요 | `delegatesToLikeService` | LikeService 위임 | 기존 |
| 좋아요 취소 | `delegatesToLikeService` | LikeService 위임 | 기존 |

### AdminProductFacade 단위 테스트 (`AdminProductFacadeTest.java`)

기존에는 상품 수정/삭제 시 캐시 무효화 검증이 없었으나, `productRedisCache.evictProductDetail()` 호출을 검증하는 테스트를 추가했다.

| 대상 | 테스트 케이스 | 검증 포인트 | 상태 |
|------|------------|-----------|------|
| 상품 생성 | `createProduct_callsBrandAndProductService` | 브랜드 조회 + 상품 등록 위임 | 기존 |
| 상품 수정 | `updatesProduct_andEvictsDetailCache` | 상품 수정 후 Redis 상세 캐시 삭제 | **추가** |
| 상품 삭제 | `deletesCartThenProduct_andEvictsDetailCache` | 장바구니 삭제 → 상품 삭제 → Redis 상세 캐시 삭제 | **추가 (기존 보완)** |

---

## 코드 리뷰 반영 사항

배포 전 코드 리뷰를 통해 발견된 이슈들을 수정했다.

### 1. userId PathVariable 미사용 보안 이슈 수정

**문제:** `GET /api/v1/products/users/{userId}/likes` 에서 `@PathVariable Long userId`를 받지만 실제로는 `@LoginMember`의 `member.getId()`만 사용하여 `userId`가 완전히 무시되고 있었다. URL상 다른 사용자의 좋아요 목록에 접근 가능한 것처럼 보이지만, 실제로는 로그인한 회원의 목록만 반환되는 보안 혼란이 있었다.

**수정:** URL을 `/api/v1/products/me/likes`로 변경하고 불필요한 `@PathVariable Long userId` 파라미터를 제거했다.

### 2. unlike HTTP 메서드 변경 (PUT → DELETE)

**문제:** 좋아요 취소가 `@PutMapping`으로 매핑되어 있었다. RESTful 관점에서 리소스 삭제/비활성화이므로 `DELETE`가 더 적합하다.

**수정:** `@PutMapping("/{productId}/likes")`를 `@DeleteMapping("/{productId}/likes")`로 변경했다.

### 3. LikeService Cross-Domain 의존성 제거 (DDD)

**문제:** `LikeService`(Like 도메인)가 `ProductRepository`(Product 도메인)에 직접 의존하고 있었다. 상품 존재 여부 확인, `incrementLikeCount`/`decrementLikeCount` 호출이 Like 도메인 서비스 내부에 있어 Bounded Context 간 결합도가 높았다.

**수정:**
- `LikeService`에서 `ProductRepository` 의존성을 제거하고, Like 엔티티의 상태 전환(생성, 활성화, 비활성화)만 담당하도록 변경
- `LikeService.like()`/`unlike()`의 반환 타입을 `Like` → `boolean`으로 변경하여, 실제 상태 변경 발생 여부를 반환
- 상품 존재 여부 확인, `likeCount` 증감은 `ProductFacade`(Application Layer)에서 `ProductService`를 통해 처리
- `ProductService`에 `incrementLikeCount()`/`decrementLikeCount()` 메서드 추가
- 상태가 변경되지 않은 경우(멱등 처리) `likeCount` 변경을 스킵하여 불필요한 DB UPDATE 방지
- `LikeService` Javadoc에 "반드시 `@Transactional` 컨텍스트에서 호출되어야 한다" 명시

```
[Before] LikeService → ProductRepository (Cross-Domain 의존)
[After]  ProductFacade → ProductService (Product 도메인)
                       → LikeService    (Like 도메인, ProductRepository 의존 없음)
```

### 4. ProductLocalCacheStore null-safety 추가

**문제:** `CacheManager.getCache()`는 `@Nullable`을 반환하지만, `ProductLocalCacheStore` 생성자에서 null 체크 없이 사용하고 있었다. 캐시 설정 누락 시 이후 호출에서 NPE가 발생할 수 있었다.

**수정:** `Objects.requireNonNull(cacheManager.getCache("productDetail"), "productDetail cache must be configured")` 패턴을 적용하여 빈 설정 누락 시 빠르게 실패하도록 했다.

### 5. 캐시 무효화 E2E 테스트 추가

**문제:** 관리자가 상품을 수정/삭제한 후 사용자 API에서 최신 데이터가 반환되는지 E2E 레벨에서 검증하는 테스트가 없었다.

**수정:** `ProductCacheEvictionE2ETest` 추가. 두 가지 시나리오를 검증한다.

| 시나리오 | 검증 포인트 |
|---------|-----------|
| 관리자 상품 수정 후 사용자 조회 | 캐시 적재 → 수정 → 캐시 삭제 확인 → 재조회 시 수정된 데이터 반환 |
| 관리자 상품 삭제 후 사용자 조회 | 캐시 적재 → 삭제 → 캐시 삭제 확인 → 재조회 시 404 NOT_FOUND |

### 6. LikeConcurrencyTest 혼합 테스트 failCount 추적 추가

**문제:** `ConcurrentLikeAndUnlikeMixed` 테스트에서 unlike/like 루프의 catch 블록에서 `failCount`를 증가시키지 않고 `// unexpected` 주석만 남겨두어, 예상치 못한 실패를 무시하고 있었다.

**수정:** `failCount` 카운터를 추가하고, catch 블록에서 `failCount.incrementAndGet()`을 호출하며, then 절에서 `failCount == 0`을 검증하도록 했다.
