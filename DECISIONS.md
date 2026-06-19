# DECISIONS.md — 기술적 쟁점 및 선택 근거

본 문서는 선착순 예약/결제 시스템을 설계하며 고민했던 **주요 기술적 쟁점**과,  
각 지점에서 어떤 대안을 비교하고 왜 특정 방향을 택했는지,  
**라이브러리 도입 사유**·**문제 해결 전략**을 정리한 것입니다.

설계 전반을 관통하는 원칙은 **이중 안전망(Defense in Depth)** 입니다.

- **Redis** — 폭주 흡수·1차 게이트 (빠름)
- **MySQL** — 최종 방어선 (느리지만 강함: UNIQUE, CHECK, 트랜잭션)

정합성·멱등성·결제 무결성처럼 한 번 깨지면 비즈니스 손실이 큰 영역은,  
Redis 1차 방어와 MySQL 최종 방어선을 **항상 두 겹**으로 둡니다.

---

## 목차

1. [재고 정합성과 공정성 — 재고를 어디서 차감할 것인가](#1-재고-정합성과-공정성--재고를-어디서-차감할-것인가)
2. [고가용성 — 동기 응답 계약을 지키면서 버스트 대응](#2-고가용성--동기-응답-계약을-지키면서-버스트-대응)
3. [중복 결제 차단 — Redis 분산 락](#3-중복-결제-차단--redis-분산-락)
4. [결제 확장성 — Strategy 패턴과 복합 결제](#4-결제-확장성--strategy-패턴과-복합-결제)
5. [Redis 장애 — fail-closed](#5-redis-장애--fail-closed)
6. [결제 실패 — 보상 트랜잭션 + Dead Letter](#6-결제-실패--보상-트랜잭션--dead-letter)
7. [Checkout 일시 장애 — 재시도 vs 매진 구분](#7-checkout-일시-장애--재시도-vs-매진-구분)
8. [라이브러리·기술 스택 선택](#8-라이브러리기술-스택-선택)
9. [발견한 버그와 해결](#9-발견한-버그와-해결)
10. [검증 전략](#10-검증-전략)
11. [인프라 비용 대비 효과](#11-인프라-비용-대비-효과)

---

## 1. 재고 정합성과 공정성 — 재고를 어디서 차감할 것인가

### 상황
2대 이상 앱 서버, 00시 10개 한정 상품에 500~1000 TPS. **초과판매 절대 불가.**  
JVM 단위 `synchronized`/`ReentrantLock`은 분산 환경에서 후보가 될 수 없음.

### 선택지

| 방안 | 장점 | 단점 | 채택 |
|------|------|------|------|
| A. MySQL `SELECT FOR UPDATE` | 정합성 확실 | 단일 row에 1000 TPS 직렬 → 커넥션 고갈 | ✗ |
| B. MySQL 낙관적 락 | 락 경합 없음 | 990건 동시 충돌 → retry storm | ✗ |
| **C. Redis Lua 원자 차감** | ~990건 DB 도달 전 탈락 | Redis SPOF | **✓ 1차** |
| MySQL 조건부 UPDATE | 최종 방어 | 느림 | **✓ 2차** |

### 왜 C를 1차 게이트로 선택했는가
A는 10개짜리 단일 재고 row에 1000 TPS가 `FOR UPDATE`로 줄을 서며 DB가 먼저 무너집니다.  
B는 990개 요청이 같은 version을 읽고 충돌·재시도하므로 폭주 구간에서 **retry storm**이 납니다.

Redis는 단일 스레드 처리이므로 Lua로 `재고 확인 → DECR → 당첨자 SET 추가`를 원자 실행할 수 있습니다.  
1000개 요청 중 ~990개는 **DB에 도달하기도 전에** Redis에서 즉시 탈락하고, 통과한 ~10건만 DB로 내려갑니다.

```lua
-- stock_decr.lua : 재고 확인 + 차감 + 당첨자 기록을 원자적으로 수행
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -3 end  -- 1인 1예약
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then return -2 end
if stock <= 0 then return -1 end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return stock - 1
```

Redis가 최종 진실이 되면 위험하므로 DB 백스톱을 둡니다.

- `uk_orders_member_product` — 1인 1예약
- `uk_orders_idem` — Checkout 멱등 키 (`checkout:{productId}:{memberId}`)
- `CHECK (remaining_stock >= 0)` — 음수 재고 방지

### 공정성에 대한 추가 판단
과제는 "선착순"이면서 "모든 사용자가 동등한 **확률**"을 요구합니다. 엄밀히는 상충할 수 있습니다.

| 해석 | 설명 | 트레이드오프 |
|------|------|-------------|
| **FIFO** (채택) | Redis 도달 순서 기반 선착순 | 구현 단순. 네트워크·회선 지연에 좌우됨 |
| **윈도우 추첨** | 오픈 직후 수백 ms 수집 후 무작위 추첨 | 네트워크 우위 상쇄. 구현·지연 복잡 |

본 구현은 **Redis 도달 순서 기반 FIFO**를 채택하되, 정책상 공정성을 더 엄격히 요구한다면  
게이트 앞단에 수집 버퍼를 두는 **윈도우 추첨**으로 확장 가능한 구조임을 명시합니다.  
k6 부하 테스트는 넓은 `memberId` 풀에서 무작위 추출로 당첨 ID 편중을 검증합니다.

---

## 2. 고가용성 — 동기 응답 계약을 지키면서 버스트 대응

### 상황
평시 50 TPS, 00시 1~5분간 500~1000 TPS. 인프라 증설 제한.  
`POST /api/bookings`는 **한 번의 요청에 동기 응답** — 폴링 구조는 API 계약 변경이라 기각.

### 선택지

| 방안 | 판단 |
|------|------|
| A. 전 과정 Tomcat 동기 | PG 구간 스레드 점유 (mock PG라 허용) |
| B. Kafka/Streams 비동기 | API 계약 변경, 컨슈머·DLT 비용 과다 → **기각** |
| C. `CompletableFuture` + `pgExecutor` | 풀(210)이 Redis보다 먼저 포화 → 대량 `503` → **기각** |
| **D. Tomcat 동기 + Redis fast-fail** | Redis가 버스트 흡수, 당첨만 `CheckoutDbGate`(10) → **채택** |

### 왜 D로 단순화했는가
C(`pgExecutor` 스레드 10 + 큐 200)는 1000 TPS 중 ~790건이 Redis 도달 전 `503 SERVER_BUSY`로 잘렸습니다.  
재고 10개 시나리오에서 실패는 `503`이 아니라 **Redis 기반 매진(200)/중복(409)** 이어야 합니다.

**현재 구현 (D):**

```
Checkout/Booking → Tomcat 동기 HTTP
  Checkout: ProductCatalogCache(Redis) → stock_decr.lua → 당첨만 CheckoutDbGate(Semaphore 10) → DB
  Booking:    OrderBookingLock → DB 결제 (당첨자 ~10명)
```

Redis 호출은 `StringRedisTemplate` **동기 블로킹**이지만 ms 단위로 끝나 1000 TPS 버스트를 흡수합니다.  
Redis Streams(`AuditStreamPublisher`)는 **처리 경로가 아닌 감사 로그** 전용입니다.

### 제거·정리한 항목 (pgExecutor 관련)

| 구분 | 항목 | 상태 |
|------|------|------|
| 코드 | `PgExecutorConfig.java` | 삭제 |
| 코드 | `CompletableFuture` + `pgExecutor` | 동기 처리로 변경 |
| 코드 | `ExceptionAdvice` — `SERVER_BUSY` / `REQUEST_TIMEOUT` | 제거 |
| 설정 | `spring.mvc.async.request-timeout` | 제거 |

---

## 3. 중복 결제 차단 — Redis 분산 락

### 상황
동일 `orderNo`에 결제 요청이 거의 동시에 2번 도달 → 둘 다 PENDING 확인 후 이중 청구 위험.

### 선택지

| 방안 | 특성 |
|------|------|
| A. `Idempotency-Key` + Redis `SET NX EX` | 성공 후에도 TTL 동안 재요청 차단 (**멱등성 토큰**) |
| **B. `orderNo` 분산 락 + unlock** | 처리 중만 잠금, 완료 후 해제 (**락**) |

### 왜 B를 선택했는가
클라이언트가 헤더를 관리할 필요 없고, 막아야 할 단위가 이미 `orderNo`로 고정되어 있습니다.  
**동시 실행 방지**가 목적이므로 락이 더 직관적입니다.

- **락 vs 멱등 토큰**: 락은 처리 **완료 후 unlock**. 멱등 키는 성공해도 TTL(5분) 동안 같은 키를 막음.
- **토큰 기반 unlock**: TTL만 믿고 `DEL`하면 만료 뒤 다른 요청의 락을 지울 수 있음 → `unlock.lua`로 **본인 토큰일 때만** 삭제.

구현 (`OrderBookingLock`):

```text
acquire(orderNo)  →  SET lock:booking:{orderNo} {token} NX EX 30
                     실패 시 DuplicateRequestException (409 DUPLICATE_REQUEST)
try { 결제 처리 }
finally { release(orderNo, token) }  →  unlock.lua (토큰 일치 시에만 DEL)
```

`BookingController`는 `Idempotency-Key` 헤더 없이 body만 받습니다.

### 이중 안전망

1. **Redis 락** — 동시에 같은 `orderNo` 결제 1건만
2. **주문 상태** — `PENDING`이 아니면 `InvalidOrderStateException`(409)
3. **Checkout `idempotency_key` UNIQUE** — 1인 1예약 DB 백스톱

PG 연동(mock) 시 `PaymentLineCommand`에 `orderNo`를 넘겨 실 PG 멱등 승인을 구조적으로 보장합니다.

---

## 4. 결제 확장성 — Strategy 패턴과 복합 결제

### 상황
결제 수단 3종(카드/Y페이/포인트), 복합 결제, 카드+Y페이 혼용 금지. 신규 수단 추가 시 Booking 수정 최소화.

### 선택지

| 방안 | 판단 |
|------|------|
| A. `if (method == CARD) ...` 분기 | OCP 위반, Booking 비대화 → **기각** |
| **B. Strategy + Map resolver** | 수단 추가 = 전략 클래스 1개 → **채택** |

### 선택
- **`PaymentStrategy`** — `pay()` / `cancel()` 인터페이스
- **`PaymentStrategyResolver`** — `Map<PaymentMethod, Strategy>` Spring 빈 자동 수집
- **`PaymentCombinationValidator`** — primary(카드·Y페이) 최대 1개, 합계 검증 (조합 정책 분리)

복합 결제는 **PaymentLine 리스트**로 모델링합니다.  
primary(카드·Y페이)와 보조(포인트)를 구분하면 "카드·Y페이 혼용 금지"는 **primary 최대 1개** 규칙으로 환원됩니다.

### 부분 실패 대응
카드 승인 후 포인트 실패 시 이미 승인된 카드를 되돌려야 합니다.  
라인을 순차 실행하다 실패하면 **성공한 결제를 역순 `cancel()`** (Saga 보상, 쟁점 6과 연결).

---

## 5. Redis 장애 — fail-closed

### 상황
재고 게이트·분산 락이 Redis에 의존 → SPOF. 장애 시 동작 정책 필요.

### 선택지

| 방안 | 판단 |
|------|------|
| **A. fail-closed (503)** | 초과판매 위험 > 일시 판매 중단 → **채택** |
| B. degraded (DB `FOR UPDATE`) | 00시 폭주와 겹치면 쟁점 1의 DB 붕괴·초과판매 위험 → **기각** |

### 왜 fail-closed인가
10개 한정 초특가에서 **"잠깐 못 파는 손해"보다 "초과판매로 약속한 상품을 못 주는 손해"가 훨씬 큽니다.**

fail-closed 전제:
- **Redis replica + Sentinel** — 마스터 장애 시 자동 페일오버 (replica 1대를 현실적 타협점)
- **DB 이중화** — Redis 락 무력화 시에도 주문 상태·`idempotency_key` UNIQUE가 중복 차단

### 구현
`ExceptionAdvice`가 `RedisConnectionFailureException`/`RedisSystemException` → `503 REDIS_UNAVAILABLE`.  
핸들러가 없으면 우연히 막힌 `500`이지 **의도된 fail-closed**가 코드로 드러나지 않습니다.

```bash
# 재현: Redis 중단 후 Checkout → 503 REDIS_UNAVAILABLE
docker compose stop redis
curl -i "http://localhost:8081/api/checkout?productId=1&memberId=1"
```

---

## 6. 결제 실패 — 보상 트랜잭션 + Dead Letter

### 상황
재고는 이미 차감됐는데 결제 실패 → **유령 재고**.

### 실패 유형 구분

| 유형 | 처리 |
|------|------|
| **영구** (잔액 부족·한도 초과) | 즉시 보상: `cancel()` 역순, 재고 복구, CANCELLED |
| **일시** (PG 타임아웃) | `payWithRetry()` 최대 2회, 50ms backoff → 실패 시 영구와 동일 보상 |

동기 API 계약과 일관되게 재처리를 별도 큐/컨슈머로 넘기지 않고 **같은 요청 스레드**에서 끝냅니다.

### Dead Letter — 이중 기록

이중 안전망 원칙을 DLT에도 동일 적용:

| 저장소 | 역할 | 시점 |
|--------|------|------|
| **MySQL `payment_dead_letter`** | 진본·SQL 조회 | `fail()` 보상과 **같은 트랜잭션** 커밋 |
| **Redis Stream `dlt:payment`** | 실시간 tail·모니터링 | fire-and-forget (실패해도 보상에 영향 없음) |

**DB가 진실의 원천**, Redis는 관측 편의. 둘이 어긋나면 DB를 따릅니다.

`@Transactional(noRollbackFor = PaymentFailedException.class)` — 보상 결과는 DB 커밋, 클라이언트에는 402.

### 이중 청구 방지
PG 재시도 시 동일 결제 이중 승인 방지를 위해 `PaymentLineCommand`에 `orderNo` 포함 (mock이지만 실연동 대비).

```bash
# 재현: 포인트 부족 결제 후 DLT 확인
docker compose exec mysql mysql -ureservepay -preservepay reservepay \
  -e "SELECT * FROM payment_dead_letter;"
docker compose exec redis redis-cli XRANGE dlt:payment - +
```

---

## 7. Checkout 일시 장애 — 재시도 vs 매진 구분

### 상황
일시 Redis/DB 장애를 매진과 동일 처리하면 억울한 탈락.  
진짜 매진을 재시도하면 초과판매 위험. **기술적 실패 vs 재고 0**을 구분해야 함.

### 선택: 원인별 분기 (B)

`CheckoutService.checkout()` → `persistWinnerWithRetry()`가 두 단계로 처리:

1. **`StockGate.reserve()`** (Redis Lua)
   - `SoldOutException` (-1) → **진짜 매진**, 즉시 종료
   - `RedisConnectionFailureException` → Redis 자체 장애, fail-closed(503) 또는 슬롯 없이 재시도
2. **`decreaseIfAvailable()`** (DB 백스톱)
   - `affected == 0` → Redis·DB 불일치 매진, `release()` 후 즉시 종료
   - `DataAccessException` / `TransactionException` → **일시 장애**, DB만 최대 5회 재시도

| 상황 | Redis 슬롯 | 처리 |
|------|------------|------|
| Lua 매진 / DB `affected=0` | **release** | 즉시 종료 |
| DB **일시** 장애 (재시도 중) | **유지** | DB만 재시도 (최대 5회, 50ms backoff) |
| 재시도 소진 (총 6회) | **release** | `booking_dead_letter` + `"예약에 실패하셨습니다."` |

### 재시도 중 슬롯을 풀지 않는 이유
Redis Lua 성공 후 DB만 실패했을 때 `release()`하면 재시도 틈에 **다른 요청이 슬롯을 가져가 초과판매** 가능.  
따라서 Redis 선점은 유지하고 **DB 단계만** 재시도합니다.  
`Order` 저장(`orderRepository.save`)은 영속 컨텍스트를 건드리므로 **재시도 대상에서 제외** — 실패 시 즉시 보상.

### Checkout Dead Letter (이중 기록, 빈도 차등)

| 저장소 | 시점 |
|--------|------|
| Redis `dlt:booking` | 재시도 중 **최초 1회만** (같은 요청 6번 찍히는 것 방지) |
| MySQL `booking_dead_letter` | **5회 재시도 소진 후 최종 포기 시만** (`attempts`=6) |

**진짜 매진은 DLT에 남기지 않음** — 정상 비즈니스 결과이기 때문.

### 사용자 응답 (둘 다 HTTP 200)

| 원인 | message | 이유 |
|------|---------|------|
| 진짜 매진 | `"판매가 종료되었습니다."` | 클라이언트가 고칠 수 없음 |
| 재시도 소진 | `"예약에 실패하셨습니다."` | 동일 |

404(상품 없음)·409(중복 예약)처럼 **요청을 고쳐야 하는 에러**는 기존처럼 별도 상태 코드 유지.

```bash
# 재현: Redis 중단 → 재시도 소진 → booking_dead_letter 기록
docker compose stop redis
curl -s "http://localhost:8081/api/checkout?productId=1&memberId=1"
docker compose start redis
docker compose exec mysql mysql -ureservepay -preservepay reservepay \
  -e "SELECT * FROM booking_dead_letter;"
```

---

## 8. 라이브러리·기술 스택 선택

### 8.1 Spring Boot 3.5 + Java 21

REST API, JPA, Redis, 트랜잭션, 예외 처리 단일 프레임워크 통합.  
`ddl-auto: none` + `schema.sql`로 스키마 재현성 확보.

### 8.2 Spring Data JPA + MySQL Connector

주문·결제 영속화, `decreaseIfAvailable()` 조건부 재고 차감.  
HikariCP `connection-timeout: 1000ms` — DB 장애 시 빠른 실패.

### 8.3 Spring Data Redis (Lettuce)

Lua 스크립트, 분산 락, 상품 캐시, 감사 Stream.  
`REJECT_COMMANDS` — 연결 끊김 시 유령 실행 방지 (9절).

### 8.4 Lombok

엔티티·서비스 보일러플레이트 감소.

### 8.5 Testcontainers

실제 MySQL·Redis 통합 테스트, CI에서 동시성·보상 반복 검증.

### 8.6 Docker Compose

`make up` — 코드 수정 없이 MySQL + Redis + 스키마 + 앱.  
`make up-distributed` — app1/app2 + Nginx 분산 실증.

### 8.7 의도적으로 도입하지 않은 것

| 기술 | 기각 이유 |
|------|-----------|
| **Apache Kafka** | 재고 10개, 동기 API, mock PG — 큐 인프라 대비 이득 없음 |
| **`pgExecutor` 풀** | Redis보다 먼저 포화, `503` 대량 발생 |
| **Redis Streams 컨슈머** | 비동기 경로 미채택, Stream은 감사만 |
| **`ddl-auto`** | `schema.sql` 수동 관리로 재현성 확보 |

---

## 9. 발견한 버그와 해결

### 9.1 Lettuce 유령 실행 (DisconnectedBehavior)

**증상:** Redis 다운 후 타임아웃으로 실패 응답을 보냈는데, Redis 복구 시 뒤늦게 Lua가 실행되어 재고가 차감됨.  
재시도는 전부 타임아웃인데 `stock`이 1 줄고 `reserved`에 회원이 들어가 있음.

**원인:** Lettuce 기본값(`DisconnectedBehavior.DEFAULT`)이 끊긴 동안 명령을 큐에 쌓았다가 재연결 시 실행.

**해결:** `RedisConfig`에서 `ClientOptions.DisconnectedBehavior.REJECT_COMMANDS`.  
연결 끊김을 즉시 감지해 `slotHeld` 판단을 신뢰할 수 있게 됨 (응답 시간도 6초대 → 0.5초대).

### 9.2 `@Modifying` 쿼리 트랜잭션 누락

**증상:** `@Transactional` 제거 후 `decreaseIfAvailable()`이 `"Executing an update/delete query"`로 즉시 실패 (연결 장애 예외가 아님).

**원인:** `findById`/`save`는 `SimpleJpaRepository` 클래스 레벨 `@Transactional`을 물려받지만,  
커스텀 `@Modifying @Query`는 **자동 트랜잭션 부여 대상이 아님**.

**해결:** `TransactionTemplate`으로 `decreaseIfAvailable()` / `increase()` 명시적 감싸기.  
AOP 진입 시점에 막혀 재시도 코드가 실행 안 되는 문제는 피하면서 트랜잭션 경계 확보.

### 9.3 `booking_dead_letter` 테이블 미생성

기존 Docker MySQL 볼륨에 신규 테이블 미반영 → `patch-missing-tables.sql` + `make patch-db` / `reset-db`.

---

## 10. 검증 전략

### 기본 실행 (단일 앱)
`make up` → MySQL + Redis + **앱 1대** (`http://localhost:8081`). 과제 재현·로컬 개발용.

### 분산 구성 (선택, 실증용)
`make up-distributed` → **app1 + app2 + Nginx LB** (`least_conn`).  
로드밸런서·다중 JVM 동작 확인용. **정합성 자체는 자동화 테스트로 증명.**

### 핵심: Nginx 없이도 2대로 늘려도 안전한 이유
모든 앱이 **동일 Redis(Lua) + MySQL(조건부 UPDATE)** 를 공유.  
앱 대수와 무관하게 같은 `StockGate`·`StockRepository`를 통과.

### 단위 테스트
- `PaymentCombinationValidatorTest` — 조합 규칙
- `YpointPaymentStrategyTest` — 포인트 pay/cancel
- `ReservePayExceptionTest` — HTTP 응답 계약

### 통합 테스트 (Testcontainers)

| 테스트 | 시뮬레이션 | 검증 |
|--------|-----------|------|
| `ConcurrentCheckoutIntegrationTest` | 50 동시 Checkout HTTP E2E | 응답 계약, 재고 10건 |
| `DistributedStockConsistencyTest` | 스레드 풀 2개(500+500) × 1000 요청 | 성공 10건, Redis·DB 초과판매 0 |
| `BookingCompensationIntegrationTest` | 복합 결제 부분 실패 | 역순 보상, 재고 복구 |
| `CheckoutSaleNotStartedTest` | 오픈 전 요청 | 403, 재고 미접촉 |

Testcontainers로 실제 MySQL·Redis를 띄우고, **물리 JVM 2개 대신 두 ExecutorService**로 app1/app2를 모델링합니다.

### k6 부하 테스트
`bash k6/prepare-load-test.sh` — 00시 500~1000 TPS 시뮬레이션, 당첨 정확히 10건·p95 < 500ms 검증.  
상세: [README.md](README.md).

---

## 11. 인프라 비용 대비 효과

| 구성요소 | 도입 | 근거 / 비용 대비 효과 |
|----------|------|----------------------|
| **MySQL** | 필수 | 주문·결제 영속 저장, UNIQUE/CHECK/트랜잭션 최종 방어선 |
| **Redis** | 필수 | 재고 Lua·분산 락·캐시를 단일 컴포넌트가 담당, 1차 방어 효익 명확 |
| **Redis Streams** | 감사만 | 추가 비용 0. **컨슈머 없음** — 처리 경로 아님 |
| **CheckoutDbGate** | 사용 | 당첨 소수만 DB(Semaphore 10), Hikari 풀과 동기화 |
| **`pgExecutor`** | **제거** | 버스트 시 `503` 유발, Redis fast-fail과 중복 |
| **Docker Compose** | 사용 | 코드 수정 없이 실행. 기본 `make up`, 분산 `make up-distributed` |
| **Redis Sentinel** | 권장 | SPOF 완화, fail-closed 발동 빈도 감소 (replica 1대 타협점) |
| **Apache Kafka** | **미도입** | 큐 규모·동기 API 계약과 불일치 |

### 종합
추가 외부 인프라는 **사실상 없습니다.** Redis를 재고·락·캐시·감사에 재사용하고,  
이 규모에 과한 도구(Kafka, 별도 큐)는 배제했습니다.

**일관된 기준:** *"이 문제 규모가 실제로 요구하는 것에만 비용을 지불한다."*

---

## 부록: 쟁점 ↔ 구현 매핑

| 쟁점 | 핵심 클래스·파일 |
|------|-----------------|
| 재고 1차 | `StockGate`, `stock_decr.lua` |
| 재고 2차 | `StockRepository`, `CheckoutDbGate` |
| 분산 락 | `OrderBookingLock`, `unlock.lua` |
| 결제 Strategy | `PaymentStrategy*`, `PaymentCombinationValidator` |
| 보상 | `BookingService.fail()` |
| Checkout 재시도 | `CheckoutService.persistWinnerWithRetry()` |
| 예외·HTTP | `ReservePayException`, `ExceptionAdvice` |
| DLT | `BookingDeadLetter`, `PaymentDeadLetter` + Publisher |
| Redis 설정 | `RedisConfig` (`REJECT_COMMANDS`) |

---

## 관련 문서

| 문서 | 내용 |
|------|------|
| [README.md](README.md) | 실행 방법·아키텍처·ERD·DDL |
| [API.md](API.md) | HTTP API 스펙 |
| [doc1.md](doc1.md) | Redis 키·Lua·Stream 상세 |
| [doc2.md](doc2.md) | 설계 원칙·pgExecutor 제거 |
| [doc3_detail.md](doc3_detail.md) | E2E 흐름·보상·상태 전이 |
| [doc4_etc.md](doc4_etc.md) | Lua 단계별·CheckoutDbGate·복합결제 예시 |
