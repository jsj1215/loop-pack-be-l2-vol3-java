# 들어가며

Spring Boot + Java 기반의 커머스 API 프로젝트를 진행하고 있다.
브랜드, 상품, 상품 옵션, 주문, 쿠폰 등 이커머스의 핵심 도메인을 다루고 있고, 상품 목록 조회 API에서는 브랜드별 필터링, 좋아요순/최신순/가격순 정렬, 페이지네이션을 지원한다.

기능 구현을 마치고 나니 "이 쿼리가 데이터가 많아져도 괜찮을까?"라는 질문이 남았다.
개발 환경에서는 데이터가 수십 건이라 어떤 쿼리든 빠르게 응답하지만, 실제 서비스에서는 상품이 수만,수십만 건으로 늘어난다.
특히 상품 목록 조회는 사용자가 가장 많이 호출하는 API이기 때문에, 데이터가 늘어났을 때 성능이 어떻게 변하는지 확인하고 미리 대응할 필요가 있었다.

그래서 조회 성능을 단계적으로 개선해 나갔고, 그 과정을 기록했다.

1. **인덱스 설계** — 10만건 데이터를 준비하고 `EXPLAIN ANALYZE`로 실행 계획을 분석하며, 쿼리 자체를 빠르게 만들었다.
2. **비정규화 (like_count)** — 좋아요 수를 별도 집계 없이 바로 정렬할 수 있도록, 데이터 구조를 개선했다.
3. **캐시 적용** — 인덱스로 빨라진 쿼리도 매번 실행할 필요는 없다. Redis / Local Cache(Caffeine) / No Cache 세 가지 전략을 구현하고 E2E 테스트로 성능을 비교했다.

---

<br/>

# Part 1. 인덱스 설계


## 1. 인덱스 개념

인덱스는 DB에서 데이터를 빠르게 찾기 위한 **정렬된 자료구조**이다.
인덱스가 없으면 테이블 전체를 읽어야 하고(Full Table Scan), 인덱스가 있으면 정렬된 구조를 통해 필요한 데이터만 빠르게 찾을 수 있다.

MySQL(InnoDB)은 **B+Tree** 구조를 사용한다. 리프 노드에만 데이터가 존재하고, 리프끼리 링크드 리스트로 연결되어 범위 검색에 유리하다.
한 노드에 키가 수백~천 개 들어가므로 1,000만 건도 높이 약 3층이며, **디스크 읽기 1~2번**으로 원하는 데이터를 찾는다.

```
                    [50]                    ← 루트 노드
                   /    \
              [25]        [75]              ← 중간 노드
             /    \      /    \
         [10,20] [30,40] [60,70] [80,90]    ← 리프 노드 (실제 데이터)
              →       →       →            ← 링크드 리스트 연결
```

- **클러스터드 인덱스**: 테이블당 1개(보통 PK). 실제 데이터가 PK 순으로 정렬 저장됨
- **세컨더리 인덱스**: 여러 개 가능. 컬럼값 + PK를 저장하고, PK로 실제 데이터를 다시 찾음
- **커버링 인덱스**: 쿼리에 필요한 모든 컬럼이 인덱스에 포함되어 있으면, 테이블 접근 없이 인덱스만으로 결과 반환 가능

---

## 2. 인덱스 실험 — 단계별 성능 비교

### 2.1 테스트 환경

상품 데이터를 10만건 이상 준비하고, 실행계획은 DBeaver에서 EXPLAIN / EXPLAIN ANALYZE 명령어와 함께 확인했다.
<details>
<summary>테스트 데이터는 이렇게 준비했다.</summary>
테스트 데이터는 SQL 스크립트로 직접 생성했다.
더미 데이터는 CLAUDE 도움을 받아 아래 구조를 바탕으로 생성될 수 있도록 하였다.
숫자 생성용 임시 테이블(`CROSS JOIN`으로 0~9999 시퀀스 생성)을 만들고, 1만건씩 10회 배치 INSERT로 상품 10만건을 넣었다.
단순히 균일한 데이터를 넣는 게 아니라, 실제 커머스 환경에 가깝도록 분포를 다양하게 설정했다:

- **like_count**: 롱테일 분포 — 60%는 0~50, 25%는 0~500, 10%는 0~3,000, 5%만 0~10,000. 실제로 대부분의 상품은 좋아요가 적고 인기 상품만 높은 패턴을 재현했다.
- **status**: ON_SALE 85%, SOLD_OUT 10%, DISCONTINUED 5%
- **display_yn**: Y 90%, N 10%
- **price**: 1,000원 ~ 500,000원 (1,000원 단위)
- **brand_id**: 80개 브랜드에 랜덤 분배 (브랜드당 약 1,250건)

데이터 분포가 편향되어야 인덱스의 효과를 제대로 확인할 수 있다. 모든 컬럼이 균등 분포면 어떤 인덱스든 비슷한 결과가 나오기 때문이다.

</details>

<br/>
현재 상품 목록 조회 쿼리는 다음과 같다.

```sql
SELECT p.*, b.name as brand_name
FROM product p
         JOIN brand b ON p.brand_id = b.id
WHERE p.deleted_at IS NULL
  AND p.status = 'ON_SALE'
  AND p.display_yn = 'Y'
  AND p.brand_id = 19          -- 브랜드 필터 (선택적)
ORDER BY p.like_count DESC     -- 정렬 조건 (like_count / created_at / price)
    LIMIT 20 OFFSET 0;
```


### 2.2 데이터가 적으면 문제를 모른다. — 3,000건 vs 10만건

먼저, 데이터가 3,000건일 때는 인덱스 없이도 문제가 없는지 확인해봤다.
PK만 있는 상태에서 동일한 쿼리를 실행한 결과이다.

| 쿼리 | 3,000건 (인덱스 없음) | 10만건 (인덱스 없음) |
|------|---------------------|-------------------|
| 브랜드 필터 + 좋아요순 | **1.06ms** | **36.7ms** |
| 전체 + 좋아요순 | **1.43ms** | **66ms** |

3,000건에서는 Full Table Scan이어도 1~3ms밖에 안 걸린다.
개발 환경에서 데이터가 수십,수천 건일 때는 어떤 쿼리든 빠르게 응답하기 때문에 성능 문제를 인지하기 어렵다. 하지만 10만건이 되면 브랜드 필터 쿼리는 약 35배, 전체 조회 쿼리는 약 46배 느려진다. 데이터가 더 늘어나면 격차는 더더더 벌어지게 된다.
이런 이유로 실제 규모에 가까운 데이터로 테스트해야 한다. 이후 분석은 10만건 기준으로 진행했다.

### 2.3 Step 1: 인덱스를 걸기 전

이 상태에서는 product 테이블에 PK(`id`)만 존재한다.

**(1) 브랜드 필터 + 좋아요 정렬**
![](https://velog.velcdn.com/images/jsj1215/post/dafb7d1d-a20c-4f77-8530-5c9e750c2de4/image.png)

```
EXPLAIN ANALYZE 결과:
--> Limit: 20 row(s)  (cost=11090 rows=20) (actual time=47.7..47.7 rows=20 loops=1)
    -> Sort: p.like_count DESC, limit input to 20 row(s) per chunk  (cost=11090 rows=99037) (actual time=47.7..47.7 rows=20 loops=1)
        -> Filter: ((p.brand_id = 19) and (p.display_yn = 'Y') and (p.`status` = 'ON_SALE') and (p.deleted_at is null))  (cost=11090 rows=99037) (actual time=0.489..47.3 rows=1051 loops=1)
            -> Table scan on p  (cost=11090 rows=99037) (actual time=0.282..42.8 rows=100000 loops=1)
```

브랜드 필터(`brand_id = 19`)는 있지만 `brand_id`에 인덱스가 없고, 정렬 컬럼에도 인덱스가 없으므로 Full Table Scan이 발생한다.
옵티마이저는 약 99,037건을 스캔할 것으로 추정했고(`cost=11090 rows=99037`), 실제로 10만건 전체를 읽은 뒤(`actual rows=100000`) 필터 조건을 통과한 1,051건을 filesort로 정렬하고 LIMIT 20을 잘라내는 구조다. **actual time=47.7ms**.

**(2) 브랜드 필터 + 최신순 정렬**
![](https://velog.velcdn.com/images/jsj1215/post/c1adb4f4-d22a-451b-afc3-30f45b10100e/image.png)

```
EXPLAIN ANALYZE 결과:
-> Limit: 20 row(s)  (cost=11090 rows=20) (actual time=42.1..42.1 rows=20 loops=1)
    -> Sort: p.created_at DESC, limit input to 20 row(s) per chunk  (cost=11090 rows=99037) (actual time=42.1..42.1 rows=20 loops=1)
        -> Filter: ((p.brand_id = 19) and (p.display_yn = 'Y') and (p.`status` = 'ON_SALE') and (p.deleted_at is null))  (cost=11090 rows=99037) (actual time=0.379..41.8 rows=1051 loops=1)
            -> Table scan on p  (cost=11090 rows=99037) (actual time=0.115..37.2 rows=100000 loops=1)
```

최신순 정렬도 마찬가지로 Full Table Scan + filesort가 발생한다. **actual time=42.1ms**.

**(3) 전체 조회 + 좋아요순 (브랜드 필터 없음)**
![](https://velog.velcdn.com/images/jsj1215/post/5503c959-2b24-423f-8340-52e41baeda75/image.png)

```
EXPLAIN ANALYZE 결과:
-> Limit: 20 row(s)  (cost=21076 rows=20) (actual time=76.4..76.4 rows=20 loops=1)
    -> Nested loop inner join  (cost=21076 rows=99037) (actual time=76.4..76.4 rows=20 loops=1)
        -> Sort: p.like_count DESC  (cost=11090 rows=99037) (actual time=76.4..76.4 rows=20 loops=1)
            -> Filter: ((p.display_yn = 'Y') and (p.`status` = 'ON_SALE') and (p.deleted_at is null))  (cost=11090 rows=99037) (actual time=0.15..47.9 rows=76347 loops=1)
                -> Table scan on p  (cost=11090 rows=99037) (actual time=0.137..35.4 rows=100000 loops=1)
        -> Single-row index lookup on b using PRIMARY (id=p.brand_id)  (cost=0.25 rows=1) (actual time=0.00157..0.00159 rows=1 loops=20)
```

브랜드 필터가 없으면 필터링할 인덱스 자체가 없어서 전체 테이블을 스캔해야 한다.
10만건 중 76,347건이 조건을 통과하고 이를 모두 정렬한다. **actual time=76.4ms**로 가장 느렸다.

### 2.4 Step 2: brand_id만 인덱스를 걸었을 때

자 그럼, 외래 키(Foreign Key) 역할을 하는 brand_id를 인덱스로 걸었을 때의 성능을 보자.

**(1) 브랜드 필터 + 좋아요 정렬**
![](https://velog.velcdn.com/images/jsj1215/post/9d8d9eb0-251c-4b51-a945-3072d1d4de4b/image.png)

```
EXPLAIN ANALYZE 결과:
-> Limit: 20 row(s)  (actual time=2.14..2.14 rows=20 loops=1)
    -> Sort: p.like_count DESC, limit input to 20 row(s) per chunk  (actual time=2.14..2.14 rows=20 loops=1)
        -> Filter: ((p.display_yn = 'Y') and (p.status = 'ON_SALE') and (p.deleted_at is null))  (actual time=0.101..2.02 rows=1051 loops=1)
            -> Index lookup on p using idx_product_copy_brand_id (brand_id=19)  (actual time=0.0983..1.88 rows=1367 loops=1)
```

brand_id 인덱스 덕에 해당 브랜드의 상품 1,367건만 스캔한다(`Index lookup → rows=1,367`).
그 후 `status`, `display_yn` 필터링을 거쳐 1,051건이 남고, 이를 메모리에서 filesort한다.
**actual time=2.14ms** — 충분히 빠르다.
다만, 현재 브랜드당 상품 수가 1,000~1,300건 수준이라 filesort 비용이 낮은 것이다. 특정 브랜드의 상품이 수만 건으로 늘어나게된다면 이 수치는 크게 나빠질 수 있다.

여기서 주의할 점이 있다. EXPLAIN 결과에 `Using filesort`가 뜨면 "인덱스를 아예 안 탄 것"으로 오해하기 쉽다.
하지만 **filesort는 "정렬에 인덱스를 사용하지 못했다"는 의미**이지, 인덱스를 전혀 사용하지 않았다는 뜻이 아니다.

인덱스 활용은 두 단계로 나눠서 봐야 한다:

| 단계 | 역할 | EXPLAIN에서 확인 |
|------|------|-----------------|
| **검색(필터링)** | WHERE 조건으로 행 찾기 | `type`, `key`, `rows` |
| **정렬(ORDER BY)** | 찾은 행을 순서대로 정렬 | `Extra`의 filesort 유무 |

위 결과에서 `key: idx_product_brand_id`로 **필터링에는 인덱스를 탔지만**, 정렬(like_count DESC)은 인덱스에 없으니 별도 filesort를 수행한 것이다.
10만건 전체를 정렬하는 것과 인덱스로 걸러낸 1,051건만 정렬하는 것은 완전히 다른 비용이다.

**(2) 브랜드 필터 + 최신순 정렬**
![](https://velog.velcdn.com/images/jsj1215/post/806b37a7-253f-466c-842b-fce5fa781865/image.png)

```
EXPLAIN ANALYZE 결과:
-> Limit: 20 row(s)  (actual time=2.81..2.82 rows=20 loops=1)
    -> Sort: p.created_at DESC, limit input to 20 row(s) per chunk  (actual time=2.81..2.81 rows=20 loops=1)
        -> Filter: ((p.display_yn = 'Y') and (p.status = 'ON_SALE') and (p.deleted_at is null))  (actual time=0.134..2.66 rows=1051 loops=1)
            -> Index lookup on p using idx_product_copy_brand_id (brand_id=19)  (actual time=0.131..2.48 rows=1367 loops=1)
```

좋아요 정렬과 동일한 패턴이다. brand_id 인덱스로 필터링 후, 걸러진 결과만 filesort.
**actual time=2.81ms** — 정렬 컬럼만 다를 뿐 성능은 거의 동일하다.

**(3) 전체 조회 + 좋아요순 (브랜드 필터 없음)**
![](https://velog.velcdn.com/images/jsj1215/post/19115aee-3ab0-4a20-bd96-4dcf1473e484/image.png)

```
EXPLAIN ANALYZE 결과:
-> Limit: 20 row(s)  (actual time=68.1..68.1 rows=20 loops=1)
    -> Nested loop inner join  (actual time=68.1..68.1 rows=20 loops=1)
        -> Sort: p.like_count DESC  (actual time=68..68 rows=20 loops=1)
            -> Filter: ((p.display_yn = 'Y') and (p.status = 'ON_SALE') and (p.deleted_at is null))  (actual time=0.0473..41 rows=76347 loops=1)
                -> Table scan on p  (actual time=0.0425..31.2 rows=100000 loops=1)
        -> Single-row index lookup on b using PRIMARY (id=p.brand_id)  (actual time=0.00163..0.00166 rows=1 loops=20)
```

brand_id 인덱스는 브랜드 필터가 없으면 사용되지 않는다.
여전히 Full Table Scan + filesort → **actual time=68.1ms로 병목 그대로**이다.

**정리: brand_id 인덱스는 브랜드 필터가 있는 쿼리만 개선하고, 전체 조회 병목은 해결하지 못한다.**

### 2.5 전체 조회 병목을 해결하려면? — 단일 인덱스 vs 복합 인덱스

brand_id 인덱스만으로는 전체 조회 병목이 해결되지 않았다.
정렬 컬럼에도 인덱스가 필요한데, 여기서 **단일 인덱스**로 갈지 **복합 인덱스**로 갈지 선택해야 했다.

**단일 인덱스**는 하나의 컬럼에만 인덱스를 거는 것이다.

```sql
CREATE INDEX idx_like_count ON product (like_count);
CREATE INDEX idx_created_at ON product (created_at);
CREATE INDEX idx_price ON product (price);
```

**복합 인덱스**는 여러 컬럼을 하나의 인덱스에 포함시키는 것이다.

```sql
CREATE INDEX idx_status_display_like ON product (status, display_yn, like_count);
```

#### 단일 인덱스의 트레이드오프

| 장점 | 단점 |
|------|------|
| 구조가 단순하고 유지보수 쉬움 | 하나의 단일 인덱스로는 필터 + 정렬을 동시에 처리 불가 |
| 다양한 쿼리에 범용적으로 활용 가능 | 옵티마이저가 한 테이블 접근 시 주된 인덱스 하나를 선택하는 경우가 많음 (Index Merge 예외 있음) |
| 옵티마이저가 상황에 따라 최적 인덱스 선택 | 선택도 낮은 조건은 필터링 효과 미미 |

실제로 단일 정렬 인덱스 3개를 만들어서 테스트해봤다.

```sql
CREATE INDEX idx_like_count ON product (like_count);
CREATE INDEX idx_created_at ON product (created_at);
CREATE INDEX idx_price ON product (price);
```

MySQL 옵티마이저는 쿼리의 조건에 따라 어떤 인덱스를 사용할지 자동으로 판단한다. 브랜드 필터가 있는 쿼리에서는 `brand_id` 인덱스를, 브랜드 필터가 없는 전체 조회에서는 정렬 컬럼의 단일 인덱스를 선택했다. 
전체 조회 병목도 해결되었고, 옵티마이저가 상황에 맞게 인덱스를 잘 골라주는 걸 확인할 수 있었다.

여기서 "그러면 단일 인덱스로 충분한 거 아닌가?"라는 생각이 들 수 있다.
하지만 현재는 ON_SALE 85%, displayYn Y 90%라서 `status`와 `display_yn`을 인덱스에 넣지 않아도 대부분 통과하지만, 실제 커머스 환경에서는 데이터 분포가 수시로 변한다:
ON_SALE + Y 비율이 50% 이하로 떨어지면 `status`와 `display_yn`의 필터링 효과가 커지고, 단일 인덱스로는 이를 활용할 수 없다.

#### 복합 인덱스의 트레이드오프

| 장점 | 단점 |
|------|------|
| 필터링 + 정렬을 하나의 인덱스에서 처리 | 인덱스 크기 증가, 쓰기 부담 증가 |
| 데이터 분포 변화에 안정적 | 선두 컬럼 원칙을 지켜야 함 |
| 불필요한 row 스캔 감소 | 정렬 조건이 추가되면 인덱스도 늘어남 |

데이터 분포 변화에 대한 안정성을 고려해서 복합 인덱스로 결정했다.

### 2.6 Step 3: 복합 인덱스 `(status, display_yn, 정렬컬럼)`을 걸었을 때

```sql
CREATE INDEX idx_product_brand_id ON product (brand_id);
CREATE INDEX idx_product_status_display_like ON product (status, display_yn, like_count);
CREATE INDEX idx_product_status_display_created ON product (status, display_yn, created_at);
CREATE INDEX idx_product_status_display_price ON product (status, display_yn, price);
```

**(1) 브랜드 필터 + 좋아요 정렬**
![](https://velog.velcdn.com/images/jsj1215/post/54052da1-0ea8-4949-9a00-b3f49a3a7eb3/image.png)

```
EXPLAIN ANALYZE 결과:
-> Limit: 20 row(s)  (actual time=1.7..1.7 rows=20 loops=1)
    -> Sort: p.like_count DESC, limit input to 20 row(s) per chunk  (actual time=1.7..1.7 rows=20 loops=1)
        -> Filter: ((p.display_yn = 'Y') and (p.status = 'ON_SALE') and (p.deleted_at is null))  (actual time=0.0846..1.55 rows=1051 loops=1)
            -> Index lookup on p using idx_product_brand_id (brand_id=19)  (actual time=0.0823..1.37 rows=1367 loops=1)
```

옵티마이저가 brand_id 인덱스를 선택했다. 브랜드 필터가 있으면 brand_id로 row를 먼저 줄이는 게 더 효율적이라고 판단한 것이다. 
기존과 동일하게 **actual time=1.7ms**로 빠르다.

**(2) 브랜드 필터 + 최신순 정렬**
![](https://velog.velcdn.com/images/jsj1215/post/f232ffc4-8c8b-4d1f-ac15-9fe0a82a9ec9/image.png)

```
EXPLAIN ANALYZE 결과:
-> Limit: 20 row(s)  (actual time=1.43..1.44 rows=20 loops=1)
    -> Sort: p.created_at DESC, limit input to 20 row(s) per chunk  (actual time=1.43..1.43 rows=20 loops=1)
        -> Filter: ((p.display_yn = 'Y') and (p.status = 'ON_SALE') and (p.deleted_at is null))  (actual time=0.0701..1.3 rows=1051 loops=1)
            -> Index lookup on p using idx_product_brand_id (brand_id=19)  (actual time=0.068..1.15 rows=1367 loops=1)
```

이것도 마찬가지로 옵티마이저가 brand_id 인덱스를 선택. **actual time=1.43ms**.

**(3) 전체 조회 + 좋아요순 (브랜드 필터 없음)**
![](https://velog.velcdn.com/images/jsj1215/post/2ad5886d-af35-4f9b-9206-66873f6f3680/image.png)

```
EXPLAIN ANALYZE 결과:
-> Limit: 20 row(s)  (actual time=0.156..0.181 rows=20 loops=1)
    -> Nested loop inner join  (actual time=0.156..0.179 rows=20 loops=1)
        -> Filter: (p.deleted_at is null)  (actual time=0.15..0.155 rows=20 loops=1)
            -> Index lookup on p using idx_product_status_display_like (status='ON_SALE', display_yn='Y')  (actual time=0.149..0.153 rows=20 loops=1)
        -> Single-row index lookup on b using PRIMARY (id=p.brand_id)  (actual time=960e-6..989e-6 rows=1 loops=20)
```

여기가 핵심이다. 브랜드 필터가 없으니 옵티마이저가 `idx_product_status_display_like` 인덱스를 선택했다.
`status = 'ON_SALE'`, `display_yn = 'Y'` 조건으로 인덱스를 탄 뒤, `like_count` 순서대로 읽기만 하면 된다.
인덱스가 이미 정렬되어 있으므로 **filesort가 발생하지 않고**, LIMIT 20에 의해 **실제로 20건만 읽고 멈춘다** (`rows=20 loops=1`로 확인 가능).

**76.4ms → 0.156ms — 약 490배 개선!**

**페이지네이션을 위한 전체 카운트 (COUNT)**

페이지네이션을 구현하려면 총 페이지 수 계산을 위한 COUNT 쿼리가 필요하다.
아래는 브랜드 필터가 포함된 COUNT 쿼리의 EXPLAIN 결과이다.

![](https://velog.velcdn.com/images/jsj1215/post/8d6eac70-1e96-456f-aee1-87d759503ad2/image.png)

```
EXPLAIN ANALYZE 결과:
-> Aggregate: count(0)  (actual time=1..1 rows=1 loops=1)
    -> Filter: ((p.display_yn = 'Y') and (p.status = 'ON_SALE') and (p.deleted_at is null))  (actual time=0.0733..0.973 rows=1051 loops=1)
        -> Index lookup on p using idx_product_brand_id (brand_id=19)  (actual time=0.0714..0.846 rows=1367 loops=1)
```

> **참고**: 이 이미지는 COUNT 쿼리의 결과이므로 ORDER BY가 없고, Backward index scan은 나타나지 않는다. Backward index scan에 대한 설명은 아래 Step 4에서 다룬다.

한편, 이번 복합 인덱스는 기본적으로 ASC로 생성되었기 때문에, `ORDER BY like_count DESC`와 같은 정렬 쿼리에서는 인덱스를 역방향으로 읽는 **Backward index scan**이 발생한다.

DB 인덱스는 기본적으로 **오름차순(ASC)**으로 정렬된 리프 노드들이 양방향 연결 리스트로 이어져 있다.
MySQL(InnoDB)의 경우 페이지 잠금(Latch) 구조상 정방향 읽기에 최적화되어 있어서
역방향 스캔이 정방향 스캔보다 느리다. 다만 MySQL 공식 벤치마크 기준 약 10~15% 차이 수준이며, LIMIT 20건만 읽는 상황에서는 체감 차이가 거의 없다.

### 2.7 Step 4: like_count와 created_at을 DESC로 인덱스를 걸었을 때

이커머스 서비스에서 `like_count`와 `created_at`의 경우는 보통 내림차순(DESC) 정렬로 진행하고,
`price`의 경우는 저가격순/고가격순 두 유형으로 정렬이 필요한 상황이 생길 수 있을 것 같았다.
대량 스캔 시에는 backward scan과 forward scan의 차이가 유의미해질 수 있으므로, `like_count`와 `created_at`의 경우 인덱스를 걸 때 DESC로 거는 게 좋겠다고 판단했다.

```sql
CREATE INDEX idx_product_status_display_like ON product (status, display_yn, like_count DESC);
CREATE INDEX idx_product_status_display_created ON product (status, display_yn, created_at DESC);
CREATE INDEX idx_product_status_display_price ON product (status, display_yn, price);  -- price는 ASC 유지
```

먼저 ASC 인덱스 상태(DESC 적용 전)에서 `EXPLAIN ANALYZE`를 확인해보자.

```
ASC 인덱스 상태 — EXPLAIN ANALYZE 결과:
-> Limit: 20 row(s)  (actual time=0.161..0.186 rows=20 loops=1)
    -> Nested loop inner join  (actual time=0.161..0.184 rows=20 loops=1)
        -> Filter: ((p.status = 'ON_SALE') and (p.deleted_at is null))  (actual time=0.155..0.162 rows=20 loops=1)
            -> Index lookup on p using idx_product_status_display_like (status='ON_SALE', display_yn='Y') (reverse)  (actual time=0.153..0.158 rows=20 loops=1)
        -> Single-row index lookup on b using PRIMARY (id=p.brand_id)  (actual time=866e-6..893e-6 rows=1 loops=20)
```

`(reverse)` 표시가 보인다 — Backward index scan이 발생하고 있다.

이제 DESC 인덱스를 적용한 후의 EXPLAIN 결과이다.

![](https://velog.velcdn.com/images/jsj1215/post/2e60ec5c-c7e5-4005-9c01-f4d7890af391/image.png)

```
DESC 인덱스 상태 — EXPLAIN ANALYZE 결과:
-> Limit: 20 row(s)  (actual time=0.156..0.181 rows=20 loops=1)
    -> Nested loop inner join  (actual time=0.156..0.179 rows=20 loops=1)
        -> Filter: (p.deleted_at is null)  (actual time=0.15..0.155 rows=20 loops=1)
            -> Index lookup on p using idx_product_status_display_like (status='ON_SALE', display_yn='Y')  (actual time=0.149..0.153 rows=20 loops=1)
        -> Single-row index lookup on b using PRIMARY (id=p.brand_id)  (actual time=960e-6..989e-6 rows=1 loops=20)
```

`(reverse)` 표시가 사라졌다 — Forward scan으로 동작하고 있음을 `EXPLAIN ANALYZE`로 직접 확인할 수 있다.
LIMIT 20건 수준에서는 실행 시간 차이가 거의 없지만(0.161ms → 0.156ms), 대량 스캔 시에는 이 차이가 유의미해질 수 있다.

MySQL 8.0부터 **Descending Index**를 지원한다.
이전 버전(5.7 이하)에서는 `DESC`를 구문상 허용하지만 실제로는 무시하고 ASC로만 저장했다.
8.0부터는 실제로 내림차순으로 B-Tree를 구성한다.

### 2.8 성능 비교 정리

| 쿼리 | 인덱스 없음 | brand_id만 | 복합 인덱스 적용 후 |
|------|-----------|-----------|----------------|
| 브랜드 + 좋아요순 | **36.7ms** (Full Scan) | **2.14ms** (brand_id) | **1.7ms** (brand_id 선택)* |
| 브랜드 + 최신순 | **36.8ms** (Full Scan) | **2.81ms** (brand_id) | **1.43ms** (brand_id 선택)* |
| 전체 + 좋아요순 | **66ms** (Full Scan) | **68.1ms** (Full Scan) | **0.156ms** (복합 인덱스) |

*\* Step 2와 Step 3의 브랜드 필터 쿼리 수치 차이(예: 2.14ms→1.7ms)는 측정 오차 범위이다. 옵티마이저가 동일하게 brand_id 인덱스를 선택했으므로 성능은 실질적으로 같다. 성능 테스트에서는 여러 번 반복 실행 후 평균을 내는 것이 더 신뢰성 있다.*

- 브랜드 필터 있는 경우: brand_id 인덱스만으로 1~2ms. 이미 충분히 빠름
- **전체 조회가 핵심 병목**: 복합 인덱스 적용으로 66ms → 0.156ms, **약 420배 개선**

---

## 3. MySQL의 정렬 방식

MySQL이 `ORDER BY`를 처리하는 방식은 두 가지이다.

- **filesort**: 조건에 맞는 row를 **먼저 다 가져온 다음** 정렬하는 방식. LIMIT 20이어도 후보 전체를 정렬한 뒤 잘라낸다. (76,525건 filesort → 20건 반환)
- **인덱스 순서 읽기**: 인덱스가 이미 정렬 순서대로 되어 있으면 **정렬 없이 순서대로 읽기만** 하면 된다. LIMIT과 결합하면 20건만 읽고 즉시 멈출 수 있다.

이것이 **66ms → 0.156ms**로 개선된 핵심 원리다.

---

## 4. 복합 인덱스 설계 — 선두 컬럼과 구성 전략

복합 인덱스로 결정한 뒤에도 고민할 것들이 남아있었다.
선두 컬럼을 뭘로 할지, brand_id를 포함시킬지 말지를 결정해야 했다.

### 4.1 선두 컬럼 선택: `brand_id` vs `status`

복합 인덱스에서 **선두 컬럼 원칙**이 중요하다.
인덱스의 첫 번째 컬럼에 조건이 없으면 그 인덱스를 효율적으로 사용할 수 없다.

전화번호부에 비유하면, 성(姓) → 이름 순으로 정렬되어 있는데 이름만으로는 찾을 수 없는 것과 같다.

**옵션 A: brand_id를 선두에 놓으면**
```sql
CREATE INDEX ... ON product (brand_id, status, display_yn, like_count);
```
- 브랜드 필터 있을 때: 선두 컬럼 매칭 → 완벽하게 동작
- 브랜드 필터 없을 때: 선두 컬럼을 건너뛰어야 함 → **전체 조회 병목 해결 안 됨**

**옵션 B: status를 선두에 놓으면**
```sql
CREATE INDEX ... ON product (status, display_yn, like_count);
```
- 브랜드 필터 없을 때: status, display_yn 매칭 → 잘 동작
- 브랜드 필터 있을 때: 기존 brand_id 인덱스가 이미 있고 1~2ms로 충분히 빠름

| 전략 | 브랜드 필터 O | 브랜드 필터 X | 비고 |
|------|-------------|-------------|------|
| A: brand_id 선두 | 완벽 최적화 | 선두 컬럼 못 탐 (비효율) | 전체 조회 병목 해결 안 됨 |
| B: status 선두 | FK 인덱스 활용 (1~2ms) | 완벽 최적화 | 전체 조회 병목 해결 |

참고로, **카디널리티가 높은(값의 종류가 많은) 컬럼을 선두에 두는 것이 일반적인 인덱스 설계 원칙**이다. `status`(3종)와 `display_yn`(2종)은 카디널리티가 매우 낮아서, 경우에 따라 옵티마이저가 이 인덱스를 외면할 수도 있다. 여기서는 "전체 상품 조회"라는 특정 병목을 해결하기 위해 조건절에 공통적으로 들어가는 `status`, `display_yn`을 전략적으로 앞에 배치한 것이다.

### 4.2 brand_id를 복합 인덱스에 포함시킬까?

status 선두로 가기로 결정한 뒤, 또 다른 고민이 있었다.
brand_id를 복합 인덱스 안에 넣을지, 아니면 별도 단일 인덱스로 분리할지였다.

**옵션 A: brand_id를 포함한 복합 인덱스**
```sql
CREATE INDEX ... ON product (status, display_yn, brand_id, like_count DESC);
```
- 브랜드 필터 + 정렬을 하나의 인덱스에서 모두 처리 가능
- 하지만 정렬 조건이 3개이므로 인덱스도 3개 필요하고, brand_id가 없는 전체 조회에서는 brand_id 자리를 건너뛰어야 함
- 인덱스 크기도 커짐

**옵션 B: brand_id는 별도 단일 인덱스로 분리**
```sql
CREATE INDEX idx_product_brand_id ON product (brand_id);
CREATE INDEX ... ON product (status, display_yn, like_count DESC);
```
- 브랜드 필터가 있으면 옵티마이저가 brand_id 인덱스를 선택 → 1~2ms로 이미 충분히 빠름
- 브랜드 필터가 없으면 복합 인덱스를 선택 → 전체 조회 병목 해결
- 각 인덱스가 역할이 명확하고 크기도 작음

옵티마이저 테스트 결과, 브랜드 필터가 있는 쿼리에서 brand_id 단일 인덱스만으로 1~2ms가 나왔다.
브랜드 하나당 상품 수가 1,000~1,300건 수준이라 filesort를 해도 부담이 없었기 때문이다.
굳이 복합 인덱스에 brand_id를 포함시켜 인덱스 크기를 늘릴 필요가 없었다.

### 4.3 최종 선택: `status` 선두 복합 인덱스 3개

```sql
CREATE INDEX idx_product_status_display_like ON product (status, display_yn, like_count DESC);
CREATE INDEX idx_product_status_display_created ON product (status, display_yn, created_at DESC);
CREATE INDEX idx_product_status_display_price ON product (status, display_yn, price);
```

선택 이유:
1. **핵심 병목 해결** — 전체 조회 + 정렬(66ms)이 가장 큰 병목이므로 이를 우선 최적화
2. **브랜드 필터는 기존 인덱스로 충분** — brand_id 인덱스가 이미 있고 1~2ms로 빠르게 동작
3. **데이터 분포 변화에 안정적** — status, display_yn이 인덱스에 포함되어 있으므로 비율 변동에도 대응 가능
4. **DESC 인덱스 적용** — like_count와 created_at은 실제 쿼리에서 DESC 정렬이므로 Backward scan 방지

### 4.4 deleted_at은 왜 인덱스에서 제외했는가

처음에는 "WHERE절에 사용되는 컬럼은 전부 인덱스에 포함시켜야 하는 거 아닌가?"라고 생각했다.
`deleted_at IS NULL` 조건이 모든 쿼리에 항상 포함되니까, 당연히 인덱스에도 넣어야 할 것 같았다.

하지만 복합 인덱스의 동작 원리를 이해하고 나니 생각이 바뀌었다.

복합 인덱스는 **선두 컬럼 기준으로 정렬**되어 있고, 옵티마이저는 인덱스로 대상을 먼저 좁힌 뒤 나머지 조건은 **Filter 단계에서 후처리**한다.
즉, WHERE절에 있다고 해서 모든 컬럼이 인덱스에 포함될 필요는 없다. 인덱스에 없는 컬럼은 옵티마이저가 알아서 Filter로 처리해준다.

실제로 EXPLAIN ANALYZE 결과를 보면 이를 확인할 수 있다.

```
-> Filter: (p.deleted_at is null)  (actual time=0.15..0.155 rows=20 loops=1)
    -> Index lookup on p using idx_product_status_display_like (status='ON_SALE', display_yn='Y')
```

`deleted_at`은 인덱스에 포함되어 있지 않지만, 인덱스로 `status`, `display_yn` 조건을 먼저 좁힌 뒤 Filter 단계에서 `deleted_at IS NULL`을 체크하고 있다.

그렇다면 "Filter로 처리해도 괜찮은가?"가 관건인데, `deleted_at`을 인덱스에서 제외해도 되는 이유는 다음과 같다.

- **선택도(selectivity)가 매우 낮다** — 현재 거의 전부가 NULL이므로 인덱스에 넣어도 필터링 효과가 없다. 인덱스는 데이터를 많이 걸러낼 수 있을 때 가치가 있다.
- **인덱스 비용만 증가한다** — 컬럼이 하나 더 추가되면 인덱스 크기가 커지고, INSERT/UPDATE마다 인덱스 유지 비용이 늘어난다. 효과 없이 비용만 올라가는 셈이다.
- **이미 소량으로 좁혀진 상태에서 체크한다** — 인덱스로 대상을 좁힌 뒤 20건 내외에서 `deleted_at IS NULL`을 체크하는 것이므로 성능 영향이 거의 없다.

다만, soft delete된 데이터가 대량으로 쌓여서 `deleted_at IS NOT NULL`인 행이 많아지면 상황이 달라진다.
그때는 Filter에서 걸러내는 비율이 커지므로 인덱스에 `deleted_at`을 포함시키거나, 파티셔닝 등의 인덱스 재설계를 고려해야 한다.

---

## 5. 인덱스의 트레이드오프와 주의할 점

인덱스는 SELECT를 빠르게 하지만, INSERT/UPDATE/DELETE마다 인덱스도 함께 갱신해야 하므로 **쓰기 성능이 느려진다.**
인덱스가 많으면 옵티마이저가 최적이 아닌 인덱스를 선택할 가능성도 높아진다.
이번 프로젝트에서도 6가지 쿼리 조합을 **4개의 인덱스**로 제한한 것도 이런 이유다.

또한 인덱스를 걸어놓더라도 다음과 같은 경우에는 인덱스를 사용하지 못한다:

```sql
WHERE YEAR(created_at) = 2025     -- 컬럼에 함수 적용 → 인덱스 사용 불가
WHERE display_yn = 1              -- 타입 불일치 (VARCHAR에 숫자 비교) → 인덱스 사용 불가
WHERE name LIKE '%키워드'          -- 앞쪽 와일드카드 → 인덱스 사용 불가
```

그리고 **카디널리티(고유값 개수)**가 낮은 컬럼은 단독 인덱스로 걸어도 필터링 효과가 미미하다.
`status`(3종), `display_yn`(2종)을 단독 인덱스로 걸지 않고 복합 인덱스의 선행 조건으로 활용한 이유이기도 하다.

---

<br/>

# Part 2. 비정규화 — 데이터 구조를 바꿔서 정렬을 빠르게

<br/>

## 1.비정규화 vs Materialized View

지금까지 인덱스를 설계하면서 `like_count` 컬럼을 당연히 있는 것처럼 사용했다.
`(status, display_yn, like_count DESC)` 복합 인덱스를 걸고, 0.2ms만에 좋아요순 정렬을 처리했다.

그런데 **`like_count`는 원래 Product 테이블에 없는 데이터다.**
좋아요 수는 Like 테이블에 기록된 좋아요 레코드를 세야 알 수 있다.
이 값을 어디에, 어떤 형태로 들고 있을 것인지에 따라 조회 성능이 완전히 달라진다.

선택지는 두 가지였다.

선택지는 두 가지였다.

- **Materialized View(MV)**: 쿼리 결과를 별도 summary 테이블에 저장하고 주기적으로 갱신. 단, MySQL 8.0은 네이티브 MV를 지원하지 않아 직접 구현 필요.
- **비정규화**: Product 테이블에 `like_count` 컬럼을 추가하고, 좋아요 등록/취소 시 원자적 UPDATE로 직접 갱신.

### 1.3 트레이드오프 비교

| 기준 | 비정규화 (`Product.likeCount`) | Materialized View (summary 테이블) |
|------|------|------|
| **조회 성능** | Product 테이블 단일 조회, 복합 인덱스 활용 가능 | JOIN 필요, 필터와 정렬이 다른 테이블에 분산 |
| **인덱스 설계** | `(status, display_yn, like_count)` 하나의 복합 인덱스로 필터 + 정렬 처리 | 필터 컬럼은 product에, 정렬 컬럼은 summary에 → 복합 인덱스로 연결 불가 |
| **실시간성** | 즉시 반영 | 갱신 주기만큼 지연 (eventual consistency) |
| **쓰기 비용** | 매 like/unlike마다 Product UPDATE 발생 | Like 테이블만 INSERT, Product 미접촉 |
| **데이터 정합성** | 버그/장애 시 실제 Like 수와 불일치 가능 | 갱신 시점에는 원본 기준 정확한 집계 보장 |
| **동시성** | 인기 상품에 좋아요가 몰리면 같은 row UPDATE 경합 (Hot Row) | Product 테이블 경합 없음 |
| **도메인 결합** | 좋아요가 Product row에 락을 잡으므로 상품 수정과 간섭 가능 | 좋아요 도메인이 상품에 의존하지 않음 |
| **구현 복잡도** | 낮음 | 높음 (MySQL 8.0 MV 미지원, 배치 직접 구현) |

### 1.4 왜 비정규화를 선택했는가?

핵심 이유는 **인덱스 설계와 직결되기 때문**이다.

앞서 인덱스 편에서 전체 좋아요순 조회의 병목을 해결한 핵심은
`(status, display_yn, like_count DESC)` 복합 인덱스였다.
필터 조건과 정렬 컬럼이 **같은 테이블, 같은 인덱스**에 있으니까
인덱스 순서대로 20건만 읽고 멈출 수 있었다.

MV 방식으로 summary 테이블을 분리하면 이 구조가 깨진다.
실제로 `product_like_summary` 테이블을 만들고 EXPLAIN으로 확인해봤다.

```sql
SELECT p.*, b.name as brand_name
FROM product p
JOIN brand b ON p.brand_id = b.id
LEFT JOIN product_like_summary pls ON pls.product_id = p.id
WHERE p.deleted_at IS NULL
  AND p.status = 'ON_SALE'
  AND p.display_yn = 'Y'
ORDER BY COALESCE(pls.like_count, 0) DESC
LIMIT 0, 20;
```

![](https://velog.velcdn.com/images/jsj1215/post/fb923581-a103-4047-bddd-d101d99ce992/image.png)

product 테이블에서 복합 인덱스로 `status`, `display_yn` 필터링은 탔지만, EXPLAIN 추정치 기준 **약 49,518건을 스캔**한다.
정렬 컬럼 `like_count`가 summary 테이블에 있기 때문에, 인덱스만으로 ORDER BY를 해소하지 못하고 filesort가 필요하다.
또한 `COALESCE(pls.like_count, 0)` 함수 적용 자체도 인덱스 활용을 방해하는 요인이다.

비정규화 방식에서는 `(status, display_yn, like_count DESC)` 복합 인덱스 덕분에 **20건만 읽고 0.2ms**에 끝났던 쿼리가,
MV 방식에서는 인덱스만으로 정렬을 해소할 수 없어 **대량 스캔 + filesort**가 필요해질 가능성이 높다.

> 참고: 이 비교는 비정규화에 유리한 조건에서 수행되었다. MV 방식에서도 summary 테이블을 driving table로 변경하는 등의 최적화 여지가 있으나, 필터 컬럼과 정렬 컬럼이 다른 테이블에 분산되는 근본적인 한계는 남는다.
`status`, `display_yn`은 product 테이블에 있고 `like_count`는 summary 테이블에 있어서,
**하나의 복합 인덱스로 필터링과 정렬을 동시에 처리할 수 없기 때문**이다.
인덱스 편에서 고생해서 없앤 filesort가 다시 돌아오는 셈이다.

그 외에도:
- **실시간 반영**: 좋아요를 누르면 바로 수치에 반영되는 것이 자연스러운 UX이다. MV는 갱신 주기만큼 지연이 생긴다.
- **구현 단순성**: MySQL 8.0에 네이티브 MV가 없어서, 결국 별도 테이블 + 배치 스케줄러를 직접 만들어야 한다. 본질적으로 "비정규화 테이블을 주기적으로 갱신"하는 것이므로 비정규화의 변형에 가깝다.
- **정합성 리스크는 관리 가능**: 정기 배치로 `likeCount`를 Like 테이블 기준으로 재계산하면 보정할 수 있다.

### 1.5 Hot Row 문제는 괜찮은가?

비정규화의 가장 큰 리스크는 인기 상품에 좋아요가 몰릴 때 같은 row에 UPDATE 경합이 발생하는 것이다.
`UPDATE product SET like_count = like_count + 1` 쿼리는 원자적(Atomic)이지만, 동시 요청 시 해당 row에 대한 **로우 락(Row Lock) 경합**이 발생하여 대기 시간이 늘어날 수 있다. 또한 좋아요가 Product row에 락을 잡으므로, 동시에 상품 정보를 수정하는 작업과 간섭할 가능성도 있다.

현재 서비스 규모에서는 단일 상품에 초당 수천 건의 좋아요가 몰리는 상황은 발생하기 어렵다고 판단했다.

만약 트래픽이 커져서 Hot Row가 문제가 된다면, MV보다는 **Redis 카운터 + 비동기 DB 반영** 전략이 더 효과적이다.

```
[요청] → Redis INCR product:{id}:like_count → 즉시 응답
        ↓ (비동기, 주기적)
      DB product.like_count 반영
```

즉시 반영 + 쓰기 경합 없음 + DB 부하 최소화로, 비정규화의 실시간성과 MV의 쓰기 분리 장점을 모두 취할 수 있다.

---

<br/>

# Part 3. 캐시 적용 — DB를 안 가는 전략

<br/>

## 1. 캐싱이란

인덱스로 쿼리를 0.2ms로 만들었지만, 트래픽이 몰리면 **매 요청마다 DB에 접근하는 것 자체**가 병목이 된다.
캐싱은 자주 요청되는 데이터를 빠른 저장소(메모리)에 복사해두고, DB 대신 돌려주는 기법이다.

인덱스가 **쿼리를 빠르게 실행**하는 것이라면, 캐시는 **쿼리 자체를 실행하지 않는 것**이다.

캐시의 진짜 가치는 단건 응답 속도가 아니라 **DB 부하 감소**에 있다.
초당 1,000 요청이 들어올 때 Hit Rate 95%면 DB 쿼리는 50번만 실행되고, 나머지 950번은 캐시에서 바로 응답한다.

### 핵심 용어

| 용어 | 설명 |
|------|------|
| **Cache Hit / Miss** | 캐시에 데이터가 있으면 Hit(바로 반환), 없으면 Miss(DB 조회 후 캐시 저장) |
| **Hit Rate** | 전체 요청 중 Hit 비율. 80% 이상이면 캐시 효과가 있다고 봄 |
| **TTL (Time To Live)** | 캐시 만료 시간. 짧으면 정합성↑ 성능↓, 길면 정합성↓ 성능↑ |
| **Eviction** | 캐시 제거. "삭제만" 하고 끝 — 다음 요청이 와야 갱신됨 (Cold Start 발생) |

---

## 2. 캐시 저장소 — Local Cache vs Remote Cache

| 구분 | Local Cache (Caffeine) | Remote Cache (Redis) |
|------|----------------------|---------------------|
| **속도** | ~1μs (네트워크 없음) | ~0.1ms (네트워크 필요) |
| **서버 간 공유** | 불가 (서버마다 독립) | 가능 (모든 서버가 같은 캐시) |
| **정합성** | 서버마다 다를 수 있음 | 일관됨 |
| **장애 영향** | 서버 재시작 시 소멸 | Redis 장애 시 전체 영향 |

다중 서버에서 Local Cache를 쓰면 서버 A에서 수정한 데이터가 서버 B 캐시에는 반영되지 않는 문제가 생긴다.
해결 방법은 Redis로 통일하거나, 짧은 TTL로 불일치를 감수하거나, Pub/Sub로 무효화하는 것이다.

---

## 3. 캐시 전략 패턴

| 기준 | Cache-Aside | Write-Through | Write-Behind |
|------|------------|--------------|-------------|
| **동작** | 읽기 시 캐시 확인, Miss면 DB 조회 후 저장 | 쓰기 시 DB+캐시 동시 갱신 | 캐시에만 쓰고 비동기로 DB 반영 |
| **읽기 성능** | Hit 시 빠름 | 항상 빠름 | 항상 빠름 |
| **쓰기 성능** | 빠름 (캐시 삭제만) | 느림 (둘 다 쓰기) | 가장 빠름 |
| **정합성** | 짧은 불일치 구간 | 높음 | 낮음 (비동기 지연) |
| **구현 복잡도** | 낮음 | 보통 | 높음 |

이번 프로젝트에서는 구현이 단순하고 캐시 장애 시 DB fallback이 가능한 **Cache-Aside**를 선택했다.

---

## 4. 캐시 도입 시 고민해야 할 것들

**캐시 키 설계** — 검색 조건 조합이 키가 된다. 조건이 많으면 키 조합이 폭발적으로 늘어나 Hit Rate가 떨어진다.

```
product:search:brandId=1:sort=LIKE_DESC:page=0:size=20
```

**캐시 무효화** — 데이터가 변경되면 어떤 캐시를 삭제해야 하는지 정확히 알기 어렵다.
TTL에 의존하거나, `product:search:*` 패턴으로 일괄 삭제하거나, 이벤트 기반으로 처리하는 방법이 있다.

**캐시 스탬피드** — TTL 만료 직후 동시 요청이 모두 Cache Miss를 겪고 DB로 폭주하는 현상.
락 기반 갱신, TTL에 랜덤 jitter 추가, 만료 전 백그라운드 갱신 등으로 대응한다.

**데이터 정합성** — 캐시와 DB 사이에는 필연적으로 불일치 구간이 생긴다.
상품 목록 순서는 수 분 지연이 허용되지만, 재고 수량은 반드시 실시간 DB 확인이 필요하다.
**"어느 정도의 불일치를 허용할 것인가"**를 서비스 특성에 맞게 판단하는 게 중요하다.

---

## 5. 인덱스와 캐시의 관계

인덱스와 캐시는 대체재가 아니라 **보완재**이다.
인덱스 없이 캐시만 도입하면, Cache Miss 시 느린 쿼리가 그대로 실행되고, 스탬피드 시 DB에 느린 쿼리가 폭주한다.

**인덱스로 쿼리 자체를 빠르게 만든 뒤, 캐시로 호출 횟수를 줄이는 것**이 올바른 순서이다.

---

## 6. 캐시 전략별 API 성능 비교 — No Cache vs Local Cache vs Redis Cache

인덱스로 DB 쿼리 자체를 빠르게 만들었지만, 실제 서비스에서는 **DB 접근 자체를 줄이는 것**이 더 효과적인 경우가 많다.
특히 상품 목록/상세 조회처럼 읽기 비율이 압도적으로 높은 API에서는 캐시가 큰 효과를 발휘한다.

이번에는 동일한 상품 목록 조회 / 상품 상세 조회 API를 세 가지 캐시 전략으로 구현하고,
E2E 테스트(TestRestTemplate + Testcontainers)로 실제 HTTP 호출을 통해 성능을 비교했다.

### 6.1 테스트 환경

- **테스트 방식**: `@SpringBootTest` + `TestRestTemplate`으로 실제 HTTP 요청을 보내는 E2E 테스트
- **인프라**: Testcontainers로 MySQL 8.0, Redis 7.0 자동 실행
- **데이터**: 브랜드 5개, 상품 50개, 옵션 각 1개
- **반복 횟수**: 각 시나리오당 50회 반복 (warm-up 1회 제외)
- **측정 방식**: `System.nanoTime()`으로 HTTP 요청~응답 전체 구간 측정

세 가지 API 엔드포인트를 각각 구현했다:

| 엔드포인트 | 캐시 전략 | 설명 |
|-----------|----------|------|
| `GET /api/v1/products` | Redis Cache-Aside | Redis에 캐시, TTL 기반 만료 |
| `GET /api/v1/products/local-cache` | Local Cache (Caffeine) | JVM 내 메모리 캐시, 크기 기반 eviction |
| `GET /api/v1/products/no-cache` | 캐시 미적용 | 매 요청마다 DB 직접 조회 |

상세 조회도 동일한 구조로 `/{productId}`, `/{productId}/local-cache`, `/{productId}/no-cache`를 제공한다.

### 6.2 E2E 테스트 — 캐시 전략별 기능 검증

성능 측정에 앞서, 각 캐시 전략이 올바르게 동작하는지 E2E 테스트로 검증했다.

**No Cache API 테스트** (`ProductNoCacheApiE2ETest`)
- 상품 목록/상세 조회 시 정상 응답 반환
- Redis에 캐시가 저장되지 않음을 확인 (`redisTemplate.keys("product:search:*")` → 빈 결과)
- 동일 조건으로 두 번 호출해도 매번 DB에서 조회하며 결과 동일

**Local Cache API 테스트** (`ProductLocalCacheApiE2ETest`)
- 첫 호출(Cache Miss) → DB 조회, 두 번째 호출(Cache Hit) → 캐시에서 반환, 결과 동일
- 3페이지(page=2)까지는 캐시 적용, 4페이지(page=3) 이상은 캐시 미적용
- 정렬 조건이 다르면 별도 캐시 키로 저장

**Redis Cache API 테스트** (`ProductRedisCacheApiE2ETest`)
- 첫 호출 시 Redis에 캐시 키가 생성됨 (`product:search:all:LATEST:all:0:10`)
- Cache Hit 시 동일 결과 반환
- 정렬/페이지 조건별 별도 캐시 키 생성 확인
- 4페이지 이상은 캐시에 저장하지 않음

모든 E2E 테스트가 통과하여 캐시 동작의 정확성을 확인한 뒤, 성능 비교를 진행했다.

### 6.3 상품 목록 조회 성능 비교

50회 반복 측정 결과 (단위: ms):

| 지표 | No Cache (DB 직접) | Redis Cache Hit | Local Cache Hit |
|------|-------------------|----------------|----------------|
| **평균** | **11.34ms** | **6.29ms** | **3.50ms** |
| 최소 | 7.94ms | 4.65ms | 2.50ms |
| 최대 | 17.86ms | 9.34ms | 5.01ms |
| **p50** | **11.16ms** | **6.37ms** | **3.40ms** |
| **p95** | **15.92ms** | **7.74ms** | **4.75ms** |
| p99 | 17.86ms | 9.34ms | 5.01ms |

Cache Miss(첫 요청) 비용:
- Redis Cache Miss: **19.12ms** (DB 조회 + Redis 저장)
- Local Cache Miss: **12.64ms** (DB 조회 + JVM 메모리 저장)

**분석**:
- Redis Cache Hit은 No Cache 대비 **약 44% 응답시간 감소** (11.34ms → 6.29ms)
- Local Cache Hit은 No Cache 대비 **약 69% 응답시간 감소** (11.34ms → 3.50ms)
- Local Cache가 Redis Cache보다 약 **1.8배 빠름** — 네트워크 왕복(Redis ↔ 앱 서버) 없이 JVM 메모리에서 바로 반환하기 때문
- Cache Miss 시에는 캐시 저장 비용이 추가되어 No Cache보다 오히려 느릴 수 있음 (Redis: 19.12ms)

### 6.4 상품 상세 조회 성능 비교

50회 반복 측정 결과 (단위: ms):

| 지표 | No Cache (DB 직접) | Redis Cache Hit | Local Cache Hit |
|------|-------------------|----------------|----------------|
| **평균** | **9.80ms** | **3.67ms** | **2.86ms** |
| 최소 | 7.09ms | 3.08ms | 2.32ms |
| 최대 | 17.63ms | 5.80ms | 4.53ms |
| **p50** | **9.48ms** | **3.54ms** | **2.74ms** |
| **p95** | **13.54ms** | **4.72ms** | **3.73ms** |
| p99 | 17.63ms | 5.80ms | 4.53ms |

**분석**:
- Redis Cache Hit은 No Cache 대비 **약 63% 응답시간 감소** (9.80ms → 3.67ms)
- Local Cache Hit은 No Cache 대비 **약 71% 응답시간 감소** (9.80ms → 2.86ms)
- 상세 조회는 목록 조회보다 캐시 효과가 더 큼 — 단일 상품 데이터는 직렬화/역직렬화 비용이 적기 때문

### 6.5 캐시 무효화(evict) 후 재조회 비용

관리자가 상품을 수정/삭제하면 캐시를 무효화하고 다음 요청에서 DB를 다시 조회한다.
이 evict → 재조회 사이클의 비용을 측정했다 (50회 반복):

| 지표 | Redis (evict 후 재조회) | Local (evict 후 재조회) |
|------|----------------------|----------------------|
| **평균** | **9.43ms** | **7.14ms** |
| 최소 | 7.12ms | 5.68ms |
| 최대 | 14.33ms | 9.31ms |
| p50 | 9.06ms | 7.08ms |
| p95 | 12.69ms | 8.76ms |

**분석**:
- evict 후 재조회는 Cache Miss와 동일한 비용 — DB 조회 + 캐시 저장
- Redis evict 재조회(9.43ms)가 Local evict 재조회(7.14ms)보다 느림 — Redis 네트워크 왕복 비용
- No Cache(9.80ms)와 큰 차이 없음 — 결국 DB를 조회하는 것이므로 당연한 결과

### 6.6 다양한 검색 조건에서의 Cache Miss → Hit 전환

정렬 조건(LATEST, LIKE_DESC, PRICE_ASC), 페이지(0, 1), 사이즈(10, 20)를 조합한 5가지 조건으로
Cache Miss → Hit 전환 비용을 측정했다:

| 캐시 전략 | Cache Miss 평균 | Cache Hit 평균 | 감소율 |
|----------|----------------|---------------|--------|
| **Redis** | 9.45ms | 4.02ms | **57.4% 감소** |
| **Local** | 8.19ms | 3.73ms | **54.5% 감소** |

다양한 조건에서도 캐시 Hit 시 일관되게 50% 이상의 응답시간 감소를 보였다.

### 6.7 캐시 전략별 트레이드오프 정리

| 기준 | No Cache | Redis Cache | Local Cache (Caffeine) |
|------|----------|-------------|----------------------|
| **응답 속도** | 가장 느림 (매번 DB) | 빠름 (네트워크 1회) | 가장 빠름 (JVM 메모리) |
| **데이터 정합성** | 항상 최신 | TTL만큼 지연 | TTL만큼 지연 |
| **멀티 인스턴스** | 해당 없음 | 모든 인스턴스 공유 | 인스턴스별 독립 (불일치 가능) |
| **장애 영향** | DB 장애 시 전체 실패 | Redis 장애 시 fallback 필요 | 앱 재시작 시 캐시 소멸 |
| **메모리 사용** | 없음 | Redis 서버 메모리 | JVM Heap 메모리 |
| **캐시 무효화** | 불필요 | Redis DEL 명령 | 인스턴스별 개별 무효화 필요 |

**정리**:
- **No Cache**: 가장 단순하지만 트래픽이 늘면 DB 부하가 그대로 전달됨
- **Redis Cache**: 멀티 인스턴스 환경에서 캐시 일관성을 보장할 수 있어 실무에서 가장 범용적
- **Local Cache**: 가장 빠르지만 멀티 인스턴스 간 데이터 불일치 가능성이 있어, 변경이 드물고 약간의 지연이 허용되는 데이터에 적합


## 마치며

이번 과제에서 가장 많이 한 건 코드를 짜는 게 아니라 **"왜?"를 반복한 것**이었다.

인덱스는 "어디에 걸까"가 아니라 **"왜 이 인덱스여야 하는가"**를 고민하는 과정이었다.
brand_id 인덱스만으로는 전체 좋아요순 조회(66ms)가 해결되지 않았고, 복합 인덱스 `(status, display_yn, like_count DESC)`를 설계하고 나서야 0.2ms로 떨어졌다.
단일 vs 복합, 선두 컬럼 선택, 데이터 분포 변화까지 — 정답이 하나가 아니었기에 EXPLAIN을 반복하며 트레이드오프를 직접 확인해야 했다.

캐시도 마찬가지였다. "Redis 붙이면 빨라지겠지"라는 단순한 기대와 달리, **캐시 키 설계, 무효화 전략, 스탬피드 대응, 정합성 허용 범위**까지 고민할 것이 많았다.
E2E 테스트로 직접 측정해보니 Local Cache가 Redis보다 1.8배 빠르지만 서버를 여러 대 사용한느 분산 환경에서는 불일치가 생기고, Redis는 네트워크 비용이 있지만 서버 간 일관성을 보장한다는 것을 체감할 수 있었다.
결국 **"어떤 캐시를 쓸까"가 아니라 "이 데이터의 특성에 어떤 전략이 맞는가"**를 판단하는 게 핵심이었다.

**개발 환경에서의 "빠르다"를 믿으면 안 된다는 것도 배웠다.**
데이터가 수십 건일 때는 어떤 쿼리든 빠르게 응답하지만, 10만건에서는 66ms vs 0.2ms로 약 420배 차이가 났다.
기능 구현이 끝났다고 넘기지 않고, 실제 규모에 가까운 데이터로 검증하는 습관이 중요하다는 걸 느꼈다.

인덱스로 쿼리를 빠르게 만들고, 캐시로 쿼리 횟수를 줄이는 것 — 이 두 단계를 직접 설계하고 측정하면서,
성능 최적화는 "기술을 아는 것"이 아니라 **"현재 상황에 맞는 트레이드오프를 판단하는 것"**이라는 걸 체감할 수 있었다.