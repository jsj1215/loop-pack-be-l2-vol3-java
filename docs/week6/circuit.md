# Circuit Breaker 개념 정리

---

## 1. 어원: 하드웨어의 회로 차단기 (Circuit Breaker)

### 전기 회로에서의 Circuit Breaker

Circuit Breaker는 원래 **전기 공학 용어**입니다. 한국어로는 **"회로 차단기"** 또는 **"누전 차단기"**라고 부릅니다.

전기 회로(Circuit)에서 **과전류(overcurrent)** 또는 **단락(short circuit)**이 발생하면, 전선이 과열되어 화재가 나거나 가전제품이 고장날 수 있습니다. 이를 방지하기 위해 회로 중간에 설치하는 안전장치가 바로 Circuit Breaker입니다.

**동작 원리:**
1. 정상 상태에서는 회로가 **닫혀(Closed)** 있어 전류가 흐릅니다.
2. 과전류가 감지되면 회로를 **열어(Open)** 전류 흐름을 차단합니다.
3. 문제가 해결된 후 수동으로(또는 자동으로) 회로를 다시 닫아 전류를 복원합니다.

핵심은 **"문제가 있는 경로를 즉시 끊어서 전체 시스템(집 전체 전기)을 보호한다"**는 것입니다.

### 하드웨어 → 소프트웨어 매핑

| 하드웨어 (전기 회로) | 소프트웨어 (분산 시스템) | 설명 |
|---|---|---|
| **전기 회로 (Circuit)** | **외부 시스템 호출 경로** | 우리 서비스 → PG사 API 같은 네트워크 호출 경로 |
| **전류 (Current)** | **요청 (Request)** | 회로를 통해 흐르는 것 = 외부로 보내는 API 요청 |
| **과전류 / 단락** | **연속 실패 / 타임아웃** | 회로에 문제가 생긴 상태 = 외부 시스템이 장애 상태 |
| **차단기 (Breaker)** | **Circuit Breaker 컴포넌트** | 호출 경로 중간에서 요청을 통과시키거나 차단하는 장치 |
| **회로 닫힘 (Closed)** | **Closed 상태** | 정상. 전류(요청)가 흐름(통과됨) |
| **회로 열림 (Open)** | **Open 상태** | 차단. 전류(요청)가 흐르지 않음(즉시 실패 반환) |
| **화재 / 가전제품 고장** | **장애 전파 / 시스템 마비** | 보호하지 않으면 발생하는 피해 |
| **퓨즈 교체 / 차단기 리셋** | **Half-Open → Closed 전이** | 문제 해결 확인 후 정상으로 복귀 |

**핵심 통찰:** 하드웨어 차단기는 "과전류가 흐르면 끊는다"이고, 소프트웨어 차단기는 "실패가 반복되면 호출을 끊는다"입니다. 둘 다 **부분적 장애가 전체 시스템으로 전파되는 것을 막는 안전장치**라는 점에서 동일합니다.

---

## 2. 소프트웨어에서의 Circuit Breaker 패턴

### 왜 필요한가?

마이크로서비스 또는 외부 시스템 연동 환경에서, 하나의 외부 서비스가 장애를 일으키면 연쇄적으로 전체 시스템이 마비될 수 있습니다.

**장애 전파 시나리오:**
```
사용자 요청 → 우리 서비스 → PG 서버 (장애 발생, 응답 지연)
                  ↓
         스레드가 PG 응답을 무한정 대기
                  ↓
         스레드 풀 고갈 (다른 요청도 처리 불가)
                  ↓
         전체 서비스 마비
```

Circuit Breaker가 없으면:
- 외부 시스템이 죽어도 계속 요청을 보냄 → **불필요한 네트워크/스레드 자원 낭비**
- 응답이 올 때까지 기다림 → **스레드 풀 고갈**
- 에러 로그가 폭발적으로 증가 → **CPU 사용률 급등**
- 결국 외부 시스템 하나의 장애가 **내부 시스템 전체 장애**로 확산

Circuit Breaker가 있으면:
- 실패가 반복되면 **아예 호출을 차단** (Fail-Fast)
- 차단된 동안은 **Fallback 응답을 즉시 반환** → 스레드 점유 없음
- 주기적으로 외부 시스템 복구 여부를 확인 → **자동 복구**

---

## 3. 세 가지 상태와 전이 (State Machine)

Circuit Breaker는 **유한 상태 머신(Finite State Machine)**으로 동작합니다.

```
                    실패율 >= 임계치
         ┌──────────────────────────────┐
         │                              ▼
     ┌────────┐                    ┌────────┐
     │ CLOSED │                    │  OPEN  │
     │ (정상)  │                    │ (차단)  │
     └────────┘                    └────────┘
         ▲                              │
         │       wait-duration 경과       │
         │                              ▼
         │                        ┌───────────┐
         │    시도 결과 성공        │ HALF-OPEN │
         └────────────────────────│ (반개방)   │
                                  └───────────┘
                                        │
                                        │ 시도 결과 실패
                                        ▼
                                   ┌────────┐
                                   │  OPEN  │
                                   └────────┘
```

### 3-1. Closed (닫힘) — 정상 상태

- 모든 요청이 외부 시스템으로 **정상적으로 전달**됩니다.
- 각 요청의 성공/실패 결과를 **슬라이딩 윈도우**에 기록합니다.
- 실패율이 설정된 임계치(`failure-rate-threshold`)를 넘으면 → **Open 상태로 전이**

**하드웨어 비유:** 차단기가 닫혀 있어서 전류(요청)가 정상적으로 흐르는 상태

### 3-2. Open (열림) — 차단 상태

- 외부 시스템으로의 모든 요청을 **즉시 차단**합니다.
- 요청을 보내지 않고 바로 **CallNotPermittedException** 예외를 던지거나 **Fallback**을 실행합니다.
- 설정된 대기 시간(`wait-duration-in-open-state`) 동안 이 상태를 유지합니다.
- 대기 시간이 경과하면 → **Half-Open 상태로 전이**

**하드웨어 비유:** 차단기가 열려서 전류(요청)가 흐르지 않는 상태. 집 전체 전기가 나간 것이 아니라, 문제 있는 회로만 차단된 것.

### 3-3. Half-Open (반개방) — 시험 상태

- 제한된 수(`permitted-number-of-calls-in-half-open-state`)의 요청만 외부 시스템으로 보냅니다.
- 이 시험 요청의 결과를 바탕으로 판단합니다:
  - **성공률이 기준 이상** → Closed 상태로 복귀 (정상 복구)
  - **여전히 실패율 초과** → 다시 Open 상태로 전이 (아직 장애 중)

**하드웨어 비유:** 차단기를 살짝 올려서 전류가 조금 흐르게 해보는 것. 문제가 없으면 완전히 올리고, 또 끊기면 다시 내림.

---

## 4. 슬라이딩 윈도우(Sliding Window)란?

Circuit Breaker의 동작 원리를 이해하기 전에, 그 핵심 메커니즘인 **슬라이딩 윈도우** 개념을 먼저 이해해야 합니다.

### 4-1. 개념: "움직이는 관찰 창"

슬라이딩 윈도우는 **"일정 범위의 데이터만 관찰하되, 그 범위가 계속 이동하는 구조"**입니다.

일상적인 비유로 설명하면:

> 기차를 타고 가면서 창문 밖을 바라본다고 생각해보세요.
> 창문(Window)의 크기는 고정이지만, 기차가 이동(Slide)하면서 창문을 통해 보이는 풍경은 계속 바뀝니다.
> 지나간 풍경은 창문 밖으로 사라지고, 새로운 풍경이 들어옵니다.

데이터 처리에서의 슬라이딩 윈도우도 동일합니다:
- **윈도우 크기(Window Size)**: 한 번에 관찰할 데이터의 범위
- **슬라이드(Slide)**: 새 데이터가 들어오면 가장 오래된 데이터가 밀려나감
- **목적**: 전체 이력이 아닌 **"최근 상태"**만으로 판단하기 위함

### 4-2. Fixed Window vs Sliding Window

슬라이딩 윈도우를 이해하려면 **고정 윈도우(Fixed Window)**와 비교하면 명확합니다.

#### Fixed Window (고정 윈도우)

```
시간 →  |---- 1분 구간 ----|---- 1분 구간 ----|---- 1분 구간 ----|
         [  요청들 집계  ]  [  요청들 집계  ]  [  요청들 집계  ]
                          ↑                  ↑
                     구간 경계에서 초기화    구간 경계에서 초기화
```

- 정해진 구간(예: 매 1분)마다 집계를 **리셋**
- **문제점 — 경계 효과(Boundary Effect):**
  ```
  |---- 1분 구간 ----|---- 1분 구간 ----|
  ................XXXXX|XXXXX...........
                      ↑
          이 10초 동안 실패가 집중되었지만,
          두 구간에 나뉘어서 각각은 임계치 미달로 판단
  ```
  구간 경계에 걸쳐 실패가 발생하면 감지하지 못하는 **사각지대**가 생깁니다.

#### Sliding Window (슬라이딩 윈도우)

```
시간 →  |----- 관찰 범위 -----|
                  |----- 관찰 범위 -----|
                            |----- 관찰 범위 -----|
        ← 새 데이터가 들어오면 창이 함께 이동 →
```

- 관찰 범위가 **연속적으로 이동**하므로 경계 효과가 없음
- 항상 **"지금 시점 기준 최근 N개/N초"**의 데이터를 관찰
- 더 정확한 실시간 판단이 가능

### 4-3. 슬라이딩 윈도우의 내부 자료구조

#### Count-based: 링 버퍼(Ring Buffer / Circular Buffer)

```
sliding-window-size: 5인 경우

초기 상태:
[ _ | _ | _ | _ | _ ]   ← 빈 링 버퍼 (크기 5)

1번째 요청(성공):
[ O | _ | _ | _ | _ ]
  ↑ head

2번째 요청(실패):
[ O | X | _ | _ | _ ]
      ↑ head

... 5번째 요청까지 채워짐:
[ O | X | O | X | X ]
                  ↑ head     실패율 = 3/5 = 60%

6번째 요청(성공) → 가장 오래된 1번째가 밀려남:
[ O | X | O | X | X ]  →  [ O | X | O | X | X ]
  ↑ 덮어씀                   ↑
                          새 결과(O)로 교체

결과: [ O(new) | X | O | X | X ]   실패율 = 3/5 = 60%
```

**링 버퍼의 특징:**
- 고정 크기 배열을 **원형으로** 사용 → 메모리 할당/해제 없음
- 새 데이터가 들어오면 가장 오래된 데이터를 **덮어씀**
- 시간 복잡도: 삽입 O(1), 실패율 계산도 집계값만 갱신하면 O(1)
- **Circuit Breaker에서의 역할:** 최근 N개 호출의 성공/실패를 추적

#### Time-based: 부분 집계 버킷(Partial Aggregation Buckets)

```
sliding-window-size: 10 (초) 인 경우

시간(초):  [0-1] [1-2] [2-3] [3-4] [4-5] [5-6] [6-7] [7-8] [8-9] [9-10]
버킷:       B0    B1    B2    B3    B4    B5    B6    B7    B8    B9

각 버킷에는 해당 1초 구간의 집계가 저장됨:
  - 총 호출 수
  - 실패 수
  - slow call 수
  - 총 소요 시간

현재 시각이 12초가 되면:
  [0-1] [1-2]는 윈도우 밖 → 폐기
  [2-3] ~ [11-12]가 현재 윈도우
```

**부분 집계의 특징:**
- 1초 단위 버킷으로 나누어 집계 → 개별 호출 기록을 보관하지 않아 메모리 효율적
- 만료된 버킷은 자동으로 폐기
- **Circuit Breaker에서의 역할:** 최근 N초간의 호출 통계를 추적

### 4-4. 슬라이딩 윈도우가 사용되는 다른 분야들

슬라이딩 윈도우는 Circuit Breaker만의 개념이 아닙니다. 소프트웨어 전반에서 광범위하게 사용됩니다:

| 분야 | 용도 | 윈도우 대상 |
|---|---|---|
| **Circuit Breaker** | 실패율 계산 | 최근 N개 호출의 성공/실패 |
| **Rate Limiter** | API 호출 제한 (초당 100회 등) | 최근 N초간의 요청 수 |
| **네트워크 (TCP)** | 흐름 제어, 혼잡 제어 | 전송 가능한 패킷 수 |
| **스트림 처리 (Kafka Streams, Flink)** | 실시간 집계 | 최근 N분간의 이벤트 |
| **알고리즘 (코딩 테스트)** | 부분 배열 최대합 등 | 연속 N개 원소 |
| **모니터링 (Prometheus)** | rate(), increase() 함수 | 최근 N초간의 메트릭 |

이처럼 **"최근 데이터만으로 판단한다"**는 동일한 원리가 다양한 맥락에서 반복됩니다.

---

## 5. Circuit Breaker에서의 슬라이딩 윈도우 적용

위에서 설명한 슬라이딩 윈도우가 Circuit Breaker에서 구체적으로 어떻게 사용되는지 살펴봅니다.

Circuit Breaker가 "실패율"을 판단하려면 최근 요청들의 성공/실패를 추적해야 합니다. Resilience4j는 두 가지 타입의 슬라이딩 윈도우를 제공합니다.

### 5-1. Count-based Sliding Window (호출 횟수 기반)

- 최근 **N개의 호출 결과**를 링 버퍼에 저장합니다.
- 예: `sliding-window-size: 10`이면 최근 10번의 호출 결과를 기반으로 실패율 계산

```
[ 성공 | 성공 | 실패 | 성공 | 실패 | 실패 | 성공 | 실패 | 실패 | 실패 ]
  → 실패 6/10 = 60% → threshold(50%) 초과 → Open!
```

**특징:**
- 구현이 단순하고 직관적
- 요청이 적은 시스템에서는 오래된 결과가 오래 남아있을 수 있음
- **이 과제에 적합**: PG 요청은 사용자 행위에 따라 발생하므로 횟수 기반이 직관적

### 5-2. Time-based Sliding Window (시간 기반)

- 최근 **N초 동안의 호출 결과**를 부분 집계(partial aggregation)로 저장합니다.
- 예: `sliding-window-size: 10` (type: TIME_BASED)이면 최근 10초간의 호출 결과를 기반으로 실패율 계산

**특징:**
- 시간이 지나면 자동으로 오래된 데이터가 빠짐
- 트래픽이 많은 시스템에 적합
- 구현이 상대적으로 복잡

### 5-3. 어떤 걸 선택해야 하는가?

| 기준 | Count-based | Time-based |
|---|---|---|
| **트래픽이 적은 서비스** | 적합 | 부적합 (윈도우 내 데이터 부족) |
| **트래픽이 많은 서비스** | 적합하나 메모리 주의 | 적합 |
| **구현 복잡도** | 낮음 | 높음 |
| **기본값 (Resilience4j)** | 기본값 | 명시적 설정 필요 |

---

## 6. 설정 항목별 상세 분석 및 선택 근거

Resilience4j의 Circuit Breaker 설정을 하나씩 분석합니다.

### 6-1. `sliding-window-type` — 윈도우 타입

```yaml
sliding-window-type: COUNT_BASED  # 또는 TIME_BASED
```

| 선택지 | 설명 | 선택 근거 |
|---|---|---|
| `COUNT_BASED` | 최근 N개의 호출 기반 | 기본값. 트래픽이 일정하지 않은 환경에 적합 |
| `TIME_BASED` | 최근 N초 동안의 호출 기반 | 초당 수백~수천 요청이 있는 고트래픽 환경에 적합 |

**이 과제에서의 선택:** `COUNT_BASED` — PG 결제 요청은 사용자 행위에 따라 산발적으로 발생하므로, 횟수 기반이 더 적합합니다.

### 6-2. `sliding-window-size` — 윈도우 크기

```yaml
sliding-window-size: 10
```

**의미:** 실패율 계산에 사용할 최근 호출 수 (COUNT_BASED 기준)

| 값 | 장점 | 단점 |
|---|---|---|
| **작은 값 (5~10)** | 장애에 빠르게 반응 | 일시적 실패에도 서킷이 열릴 수 있음 (민감) |
| **큰 값 (20~50)** | 안정적 판단, 노이즈에 강함 | 장애 감지가 느림 |

**선택 근거:**
- PG Simulator의 요청 성공률이 60%이므로 윈도우가 너무 작으면 정상 상황에서도 서킷이 열릴 수 있음
- `10`은 충분한 샘플로 판단하면서도 빠르게 반응할 수 있는 균형점
- **주의:** sliding-window-size만큼의 호출이 쌓여야 실패율 계산이 시작됨. 그 전까지는 서킷이 열리지 않음 (`minimum-number-of-calls` 설정으로 조절 가능)

### 6-3. `failure-rate-threshold` — 실패율 임계치

```yaml
failure-rate-threshold: 50  # 퍼센트(%)
```

**의미:** 이 비율을 넘으면 Closed → Open으로 전이

| 값 | 의미 | 적합한 상황 |
|---|---|---|
| **낮은 값 (30~40%)** | 보수적. 조금만 실패해도 차단 | 결제 같은 크리티컬 서비스 |
| **중간 값 (50%)** | 균형. 절반 이상 실패해야 차단 | 일반적인 외부 연동 |
| **높은 값 (70~80%)** | 관대함. 대부분 실패해야 차단 | 실패 허용도가 높은 서비스 |

**선택 근거:**
- PG Simulator 자체의 요청 성공률이 60%이므로, `failure-rate-threshold`를 50%로 잡으면 정상 상황에서는 서킷이 열리지 않음 (실패율 40%)
- 만약 PG가 진짜 장애 상태가 되어 성공률이 50% 이하로 떨어지면 서킷이 열림
- **함정 주의:** PG 성공률 60%인데 threshold를 30%로 잡으면 정상 상황에서도 서킷이 열릴 수 있음

### 6-4. `slow-call-duration-threshold` — 느린 호출 기준 시간

```yaml
slow-call-duration-threshold: 2s
```

**의미:** 이 시간보다 오래 걸린 호출은 "느린 호출(slow call)"로 분류

**왜 필요한가:**
- 외부 시스템이 "실패"는 아닌데 매우 느리게 응답하는 경우
- 느린 응답도 스레드를 오래 점유하므로, **실질적으로 실패와 동일한 영향**을 줌
- 예: PG가 4초 만에 응답을 주면 에러는 아니지만, 이런 요청이 쌓이면 시스템이 느려짐

**선택 근거:**
- PG Simulator의 요청 지연이 100~500ms이므로, 정상 범위의 2~4배인 `2s`가 적절
- 이보다 짧으면 정상 지연도 slow call로 잡힐 수 있음
- 이보다 길면 실제로 문제가 있는 지연을 감지하지 못함

### 6-5. `slow-call-rate-threshold` — 느린 호출 비율 임계치

```yaml
slow-call-rate-threshold: 50  # 퍼센트(%)
```

**의미:** 느린 호출의 비율이 이 값을 넘으면 Open으로 전이

**선택 근거:**
- `failure-rate-threshold`와 별개로 동작
- 요청의 절반 이상이 느린 응답이면 외부 시스템에 문제가 있다고 판단
- 두 조건 중 **하나라도 충족**되면 서킷이 열림

### 6-6. `wait-duration-in-open-state` — Open 유지 시간

```yaml
wait-duration-in-open-state: 10s
```

**의미:** Open 상태에서 Half-Open으로 전이하기까지 대기하는 시간

| 값 | 장점 | 단점 |
|---|---|---|
| **짧은 값 (5s)** | 빠른 복구 시도 | 아직 장애 중이면 불필요한 재시도 |
| **중간 값 (10~30s)** | 외부 시스템 복구 시간 확보 | 복구가 빠른 경우 불필요한 대기 |
| **긴 값 (60s+)** | 외부 부하 최소화 | 복구 후에도 오래 차단됨 |

**선택 근거:**
- PG Simulator의 처리 지연이 1~5초이므로 10초면 충분한 복구 시간
- 너무 짧으면 PG가 아직 복구 중인데 다시 요청을 보내게 됨
- 너무 길면 PG가 정상으로 돌아와도 우리 시스템이 계속 차단 상태

### 6-7. `permitted-number-of-calls-in-half-open-state` — Half-Open 시 허용 호출 수

```yaml
permitted-number-of-calls-in-half-open-state: 3
```

**의미:** Half-Open 상태에서 외부 시스템으로 보낼 시험 요청 수

| 값 | 장점 | 단점 |
|---|---|---|
| **작은 값 (1~2)** | 외부에 최소한의 부하 | 판단 근거 부족 (1개 요청으로 판단) |
| **중간 값 (3~5)** | 적절한 샘플로 판단 | - |
| **큰 값 (10+)** | 정확한 판단 | 아직 장애 중이면 불필요한 요청 다수 |

**선택 근거:**
- PG 성공률이 60%이므로, 1~2개만으로는 운이 좋아서/나빠서 잘못 판단할 수 있음
- 3개면 통계적으로 어느 정도 신뢰할 수 있는 샘플

### 6-8. `minimum-number-of-calls` — 최소 호출 수

```yaml
minimum-number-of-calls: 5
```

**의미:** 실패율을 계산하기 시작하는 최소 호출 수. 이 수에 도달하기 전까지는 실패율을 계산하지 않음.

**왜 필요한가:**
- 서버 시작 직후 첫 2개 요청이 모두 실패하면 실패율 100% → 바로 서킷이 열림
- 이는 의도한 동작이 아님. 충분한 샘플이 모일 때까지는 판단을 보류해야 함

**선택 근거:**
- `sliding-window-size`보다 같거나 작은 값으로 설정
- 5개 정도면 초기 노이즈를 필터링하면서도 빠르게 실패율 계산을 시작할 수 있음

### 6-9. `register-health-indicator` — 헬스 체크 등록

```yaml
register-health-indicator: true
```

**의미:** Spring Boot Actuator의 `/actuator/health` 엔드포인트에 서킷 브레이커 상태를 노출

**왜 필요한가:**
- 운영 중 서킷 브레이커의 현재 상태(Closed/Open/Half-Open)를 모니터링
- Grafana 등 모니터링 도구와 연동하여 알림 설정 가능

---

## 7. Resilience4j 전체 설정 예시와 해석

```yaml
resilience4j:
  circuitbreaker:
    instances:
      pgCircuit:
        # --- 슬라이딩 윈도우 설정 ---
        sliding-window-type: COUNT_BASED        # 호출 횟수 기반으로 실패율 계산
        sliding-window-size: 10                 # 최근 10개 호출 기준

        # --- 실패 판단 기준 ---
        failure-rate-threshold: 50              # 실패율 50% 초과 시 Open
        slow-call-duration-threshold: 2s        # 2초 이상 걸리면 slow call
        slow-call-rate-threshold: 50            # slow call 비율 50% 초과 시 Open
        minimum-number-of-calls: 5              # 최소 5번 호출 후부터 실패율 계산

        # --- 상태 전이 설정 ---
        wait-duration-in-open-state: 10s        # Open → Half-Open 전이 대기 시간
        permitted-number-of-calls-in-half-open-state: 3  # Half-Open에서 시험 요청 수
        automatic-transition-from-open-to-half-open-enabled: true  # 자동 전이 활성화

        # --- 모니터링 ---
        register-health-indicator: true         # Actuator 헬스 체크에 노출
```

### 이 설정이 만드는 동작 흐름

```
1. 서버 시작 → Closed 상태
2. 요청 처리 시작, 최근 10개 호출 결과를 추적
3. (최소 5번 호출 후부터) 실패율 또는 slow call 비율이 50% 초과
   → Open 상태로 전이, 모든 요청 즉시 차단 + Fallback 실행
4. 10초 대기
5. Half-Open 상태로 전이, 3개의 시험 요청 허용
6-A. 시험 요청이 정상 → Closed로 복귀
6-B. 시험 요청이 여전히 실패 → 다시 Open으로 전이, 10초 대기 반복
```

---

## 8. Circuit Breaker와 다른 패턴의 조합

Circuit Breaker는 단독으로도 사용하지만, **Retry, Timeout, Fallback과 조합**했을 때 진정한 Resilience를 달성합니다.

### 8-1. 실행 순서 (Resilience4j의 데코레이터 순서)

```
요청 → Retry → CircuitBreaker → TimeLimiter → 실제 호출
```

**왜 이 순서인가:**
- **Retry가 가장 바깥**: 실패한 전체 흐름을 재시도
- **CircuitBreaker가 중간**: 서킷이 Open이면 재시도 자체를 하지 않음 (불필요한 재시도 방지)
- **TimeLimiter가 가장 안쪽**: 개별 호출의 시간 제한

### 8-2. Retry + Circuit Breaker 조합

```
요청 → [Retry: 최대 3번] → [CircuitBreaker: 실패율 추적] → PG 호출
```

**동작 시나리오:**
1. PG 호출 실패 → Retry가 재시도 (1차)
2. 또 실패 → Retry가 재시도 (2차)
3. 또 실패 → Retry가 최대 시도 초과, 실패로 확정
4. CircuitBreaker의 슬라이딩 윈도우에 "실패" 기록
5. 이런 실패가 누적되어 임계치를 넘으면 → 서킷 Open
6. 서킷이 Open되면 Retry 자체가 의미 없음 → 즉시 Fallback 실행

**핵심:** Retry는 "일시적 실패를 재시도로 극복", Circuit Breaker는 "반복적 실패 시 아예 호출 차단". 두 가지의 역할이 다릅니다.

### 8-3. Fallback의 위치

```java
@CircuitBreaker(name = "pgCircuit", fallbackMethod = "paymentFallback")
@Retry(name = "pgRetry")
public PaymentResponse requestPayment(PaymentRequest request) {
    return pgClient.requestPayment(request);
}

// Retry를 모두 소진하거나, CircuitBreaker가 Open일 때 실행됨
public PaymentResponse paymentFallback(PaymentRequest request, Throwable t) {
    // 결제 보류(PENDING) 상태로 처리
    return PaymentResponse.pending("결제 처리 중입니다. 잠시 후 확인해주세요.");
}
```

**Fallback이 호출되는 경우:**
1. Retry를 모두 소진한 후 최종 실패 시
2. Circuit Breaker가 Open 상태에서 요청이 들어올 때 (`CallNotPermittedException`)
3. Timeout이 발생했을 때

---

## 9. 실패로 간주되는 것과 안 되는 것

### 기본 동작

Resilience4j의 Circuit Breaker는 기본적으로 **모든 예외(Exception)**를 실패로 간주합니다.

### 세부 설정

```yaml
resilience4j:
  circuitbreaker:
    instances:
      pgCircuit:
        record-exceptions:            # 이 예외들만 실패로 기록
          - java.io.IOException
          - java.util.concurrent.TimeoutException
          - feign.FeignException
        ignore-exceptions:            # 이 예외들은 실패로 기록하지 않음
          - com.loopers.support.exception.BusinessException
```

**왜 구분해야 하는가:**
- `400 Bad Request` (잘못된 카드번호) → 외부 시스템 장애가 아니라 **클라이언트 입력 오류** → 실패로 기록하면 안 됨
- `500 Internal Server Error` → 외부 시스템 장애 → 실패로 기록해야 함
- `SocketTimeoutException` → 네트워크 장애 → 실패로 기록해야 함

**이 과제에서의 판단:**
- PG의 "잘못된 카드(10%)"나 "한도 초과(20%)"는 PG 장애가 아닌 비즈니스 실패 → `ignore-exceptions`에 해당
- PG 서버 자체의 응답 실패(40%)나 타임아웃 → `record-exceptions`에 해당
- 이 구분을 잘못하면 정상 상황에서도 서킷이 열리는 문제가 발생

---

## 10. Circuit Breaker 테스트 전략

### 10-1. 단위 테스트: CircuitBreaker 상태 전이 검증

CircuitBreaker의 상태 전이를 직접 제어하며 테스트합니다.

```java
@Test
void 서킷이_Open_상태이면_즉시_Fallback이_실행된다() {
    // given
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .slidingWindowSize(5)
        .failureRateThreshold(50)
        .waitDurationInOpenState(Duration.ofSeconds(10))
        .build();

    CircuitBreaker circuitBreaker = CircuitBreaker.of("test", config);

    // 서킷을 강제로 Open 상태로 전이
    circuitBreaker.transitionToOpenState();

    // when & then
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThatThrownBy(() ->
        circuitBreaker.decorateRunnable(() -> externalCall()).run()
    ).isInstanceOf(CallNotPermittedException.class);
}
```

### 10-2. 단위 테스트: 실패율에 따른 상태 전이

```java
@Test
void 실패율이_임계치를_넘으면_서킷이_Open된다() {
    // given
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .slidingWindowSize(4)
        .minimumNumberOfCalls(4)
        .failureRateThreshold(50)
        .build();

    CircuitBreaker circuitBreaker = CircuitBreaker.of("test", config);

    // when - 4번 중 3번 실패 (75% 실패율)
    circuitBreaker.onSuccess(0, TimeUnit.MILLISECONDS);  // 성공
    circuitBreaker.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());  // 실패
    circuitBreaker.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());  // 실패
    circuitBreaker.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());  // 실패

    // then
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
}
```

### 10-3. 통합 테스트: Spring Boot + Resilience4j 어노테이션 테스트

실제 Spring 컨텍스트에서 `@CircuitBreaker` 어노테이션이 동작하는지 검증합니다.

```java
@SpringBootTest
class PaymentCircuitBreakerIntegrationTest {

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockBean
    private PgClient pgClient;  // 외부 호출을 Mock 처리

    @Test
    void PG_연속_실패_시_서킷이_열리고_Fallback이_실행된다() {
        // given - PG가 항상 실패하도록 설정
        when(pgClient.requestPayment(any()))
            .thenThrow(new FeignException.InternalServerError("PG 장애", ...));

        CircuitBreaker circuitBreaker = circuitBreakerRegistry
            .circuitBreaker("pgCircuit");

        // when - sliding-window-size만큼 요청
        for (int i = 0; i < 10; i++) {
            paymentFacade.requestPayment(createTestRequest());
        }

        // then - 서킷이 Open 상태
        assertThat(circuitBreaker.getState())
            .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void 서킷_Open_상태에서_요청하면_Fallback_응답을_받는다() {
        // given - 서킷을 강제로 Open
        CircuitBreaker circuitBreaker = circuitBreakerRegistry
            .circuitBreaker("pgCircuit");
        circuitBreaker.transitionToOpenState();

        // when
        PaymentResponse response = paymentFacade.requestPayment(createTestRequest());

        // then - Fallback 응답 확인
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).contains("결제 처리 중");

        // PG 클라이언트가 호출되지 않았음을 검증
        verify(pgClient, never()).requestPayment(any());
    }
}
```

### 10-4. 테스트 환경 설계 시 고려사항

#### 테스트용 application.yml 설정

테스트에서는 빠른 피드백을 위해 값을 작게 조정합니다.

```yaml
# application-test.yml
resilience4j:
  circuitbreaker:
    instances:
      pgCircuit:
        sliding-window-size: 4              # 테스트에서는 작게
        minimum-number-of-calls: 4
        failure-rate-threshold: 50
        wait-duration-in-open-state: 1s     # 테스트에서는 짧게
        permitted-number-of-calls-in-half-open-state: 2
```

#### 외부 시스템 Mock 전략

| 방법 | 장점 | 단점 | 적합한 테스트 |
|---|---|---|---|
| **@MockBean** | 간단, 빠름 | 실제 HTTP 통신 없음 | 단위/통합 테스트 |
| **WireMock** | 실제 HTTP, 지연/실패 시뮬레이션 | 설정이 복잡 | 통합/E2E 테스트 |
| **PG Simulator 직접 사용** | 가장 현실적 | 외부 의존성, 비결정적 | E2E 테스트 |

#### WireMock을 활용한 장애 시뮬레이션

```java
@WireMockTest(httpPort = 8082)
class PaymentCircuitBreakerE2ETest {

    @Test
    void PG_서버_500_에러_시_서킷이_열린다() {
        // given - PG가 500 에러를 반환하도록 설정
        stubFor(post(urlEqualTo("/api/v1/payments"))
            .willReturn(aResponse()
                .withStatus(500)
                .withBody("Internal Server Error")));

        // when & then ...
    }

    @Test
    void PG_서버_지연_시_slow_call로_감지된다() {
        // given - PG가 3초 지연되도록 설정
        stubFor(post(urlEqualTo("/api/v1/payments"))
            .willReturn(aResponse()
                .withFixedDelay(3000)  // 3초 지연
                .withStatus(200)
                .withBody("{...}")));

        // when & then ...
    }

    @Test
    void PG_서버_복구_시_Half_Open에서_Closed로_전이된다() {
        // given - 먼저 실패로 서킷을 Open시킴
        stubFor(post(urlEqualTo("/api/v1/payments"))
            .willReturn(aResponse().withStatus(500)));

        // 서킷이 Open될 때까지 요청
        // ...

        // wait-duration 경과 후 PG 정상 응답으로 변경
        stubFor(post(urlEqualTo("/api/v1/payments"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"txId\": \"test-tx\", \"status\": \"SUCCESS\"}")));

        // when - Half-Open 상태에서 시험 요청
        // then - Closed로 전이됨을 검증
    }
}
```

### 10-5. 테스트 시나리오 체크리스트

| # | 시나리오 | 검증 포인트 |
|---|---|---|
| 1 | 정상 요청 | Closed 유지, 정상 응답 반환 |
| 2 | 실패율 임계치 초과 | Closed → Open 전이 |
| 3 | Open 상태에서 요청 | 즉시 Fallback 실행, 외부 호출 없음 |
| 4 | Open → Half-Open 전이 | wait-duration 경과 후 전이 |
| 5 | Half-Open에서 성공 | Half-Open → Closed 복귀 |
| 6 | Half-Open에서 실패 | Half-Open → Open 재전이 |
| 7 | Slow call 감지 | 느린 응답이 실패로 집계됨 |
| 8 | 비즈니스 예외 무시 | ignore-exceptions에 해당하는 예외는 실패로 집계되지 않음 |
| 9 | Retry + CircuitBreaker 조합 | Retry 소진 후 실패가 서킷에 기록됨 |
| 10 | Fallback 내부 로직 | PENDING 상태 저장, 사용자 응답 정상 반환 |

---

## 11. 운영 시 모니터링 포인트

Circuit Breaker를 적용했다면, 반드시 **현재 상태를 모니터링**할 수 있어야 합니다.

### Actuator 엔드포인트

```
GET /actuator/health
GET /actuator/circuitbreakers
GET /actuator/circuitbreakerevents
```

### Prometheus + Grafana 연동 시 주요 메트릭

| 메트릭 | 설명 |
|---|---|
| `resilience4j_circuitbreaker_state` | 현재 서킷 상태 (0=CLOSED, 1=OPEN, 2=HALF_OPEN) |
| `resilience4j_circuitbreaker_calls_total` | 호출 수 (successful, failed, not_permitted) |
| `resilience4j_circuitbreaker_failure_rate` | 현재 실패율 |
| `resilience4j_circuitbreaker_slow_call_rate` | 현재 slow call 비율 |

### 알림 설정 기준 (예시)

- 서킷이 **Open** 상태로 전이되면 → **즉시 알림** (Slack, PagerDuty 등)
- 서킷이 **30초 이상 Open** 상태를 유지하면 → **긴급 알림**
- **실패율이 40%를 넘으면** → **경고 알림** (Open되기 전에 미리 감지)

---

## 12. 정리: Circuit Breaker 도입 시 의사결정 흐름

```
1. 외부 시스템 호출이 있는가?
   └─ YES → 타임아웃 설정 (전제 조건)

2. 실패가 반복될 수 있는가?
   └─ YES → Circuit Breaker 도입 검토

3. 윈도우 타입은?
   └─ 트래픽 적음 → COUNT_BASED
   └─ 트래픽 많음 → TIME_BASED

4. 실패율 임계치는?
   └─ 외부 시스템의 정상 실패율 파악 → 그보다 높게 설정

5. Open 유지 시간은?
   └─ 외부 시스템의 복구 예상 시간 기준으로 설정

6. Fallback 전략은?
   └─ 비즈니스 요구사항에 맞게 결정
      (보류 처리 / 대체 경로 / 캐시된 응답 / 즉시 실패)

7. 어떤 예외를 실패로 기록할 것인가?
   └─ 외부 시스템 장애(5xx, Timeout) → record
   └─ 비즈니스 실패(4xx, 잘못된 입력) → ignore

8. 모니터링 설정
   └─ 서킷 상태 알림 + 실패율 대시보드
```
