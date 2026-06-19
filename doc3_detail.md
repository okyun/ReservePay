# ReservePay 전체 흐름 상세

[doc1.md](doc1.md)(Redis 키·Lua·Stream) · [doc2.md](doc2.md)(설계 원칙·pgExecutor 제거)를 보완하는 **엔드투엔드 흐름** 문서입니다.  
`stock_decr.lua` 단계별 해설, `CheckoutDbGate` 재시도, 복합결제 요청 예시는 [doc4_etc.md](doc4_etc.md)를 참고하세요.

---

## 한 줄 요약

**Tomcat 동기 HTTP → Redis가 1차 문지기(매진·중복·락) → MySQL은 당첨·결제 확정만 → Redis 장애 시 503 fail-closed**

선착순 예약은 **Checkout(재고 경쟁)** 과 **Booking(결제 확정)** 두 단계로 나뉩니다.

---

## 1. 사용자 관점 전체 여정

```mermaid
flowchart LR
    subgraph Phase1["① Checkout — 재고 경쟁"]
        A[GET /api/checkout] --> B{Redis}
        B -->|당첨| C[Order PENDING]
        B -->|매진/중복| D[즉시 종료]
    end

    subgraph Phase2["② Booking — 결제 확정"]
        C --> E[POST /api/bookings]
        E --> F{결제 Strategy}
        F -->|성공| G[Order CONFIRMED]
        F -->|실패| H[보상 트랜잭션]
        H --> I[Order CANCELLED + 재고 복구]
    end

    G --> J[예약 완료]
```

| 단계 | API | 질문 | 트래픽 규모 |
|------|-----|------|-------------|
| **Checkout** | `GET /api/checkout?productId=&memberId=` | 재고 있나? 1인1예약 가능? | 00시 **500~1000 TPS** |
| **Booking** | `POST /api/bookings` | 이 주문 결제할 수 있나? | 당첨자 **~10건** 수준 |

---

## 2. 시스템 아키텍처 (doc2 원칙 4가지)

```mermaid
flowchart TB
    subgraph Clients["클라이언트"]
        U[사용자 N명]
    end

    subgraph LB["인프라"]
        NG[Nginx]
        APP1[app1 Tomcat]
        APP2[app2 Tomcat]
    end

    subgraph Redis["Redis — 공유 1차 방어"]
        CAT["product:{id}:opening_at / price"]
        STOCK["stock:{id} + reserved:{id}"]
        LOCK["lock:booking:{orderNo}"]
        STREAM["events:* / dlt:*"]
    end

    subgraph MySQL["MySQL — 최종 방어"]
        DB[(orders, stock, payment, point_history)]
    end

    U --> NG
    NG --> APP1 & APP2
    APP1 & APP2 --> Redis
    APP1 & APP2 -->|당첨·결제만| DB

    style Redis fill:#fef3c7
    style MySQL fill:#dbeafe
```

| # | 원칙 (doc2) | 의미 |
|---|-------------|------|
| 1 | **실패는 Redis에서 빨리** | 503이 아니라 매진(200) / 중복(409) |
| 2 | **DB는 소수만** | Redis 통과 당첨자 ~10건만 DB |
| 3 | **Redis 장애 fail-closed** | `503 REDIS_UNAVAILABLE` — 판매 중단 |
| 4 | **앱 N대도 같은 Redis** | Lua·Lock으로 분산 정합성 |

---

## 3. Checkout 상세 흐름

### 3-1. 처리 순서

```mermaid
sequenceDiagram
    participant C as 클라이언트
    participant T as Tomcat
    participant CS as CheckoutService
    participant PC as ProductCatalogCache
    participant SG as StockGate (Lua)
    participant G as CheckoutDbGate (Semaphore 10)
    participant DB as MySQL
    participant R as Redis Stream

    C->>T: GET /api/checkout
    T->>CS: checkout(productId, memberId)

    Note over CS,PC: ① 오픈 확인 (DB 없음)
    CS->>PC: requireOpen(productId)
    alt 오픈 전
        CS-->>C: 403 SALE_NOT_STARTED
    end

    Note over CS,SG: ② 재고·1인1예약 (99% 여기서 종료)
    CS->>SG: reserve() → stock_decr.lua
    alt 매진 (-1)
        CS-->>C: 200 success:false
    else 중복 (-3)
        CS-->>C: 409 DUPLICATE_RESERVATION
    else Redis 당첨
        Note over CS,G: ③ 당첨자만 DB (동시 10)
        CS->>G: persistWinnerWithRetry()
        G->>DB: decreaseIfAvailable()
        alt affected=0 (DB 매진)
            G->>SG: release() 보상
            CS-->>C: 200 매진
        else 성공
            G->>DB: Order PENDING 저장
            G->>R: XADD events:order
            CS-->>C: 200 success + orderNo
        end
    end
```

### 3-2. Redis 키 변화 (당첨 시)

```
Before:  stock:1 = "10"     reserved:1 = {}
After:   stock:1 = "9"      reserved:1 = {memberId}
```

`stock_decr.lua`가 **DECR + SADD**를 원자적으로 수행합니다. ([doc4_etc.md](doc4_etc.md) Lua 단계별 참고)

### 3-3. CheckoutDbGate — 왜 필요한가

Redis 당첨 후에도 DB에서 `decreaseIfAvailable`이 0이면(레이스·동기화 지연) **Redis 슬롯을 release**하고 매진 처리합니다.

```
당첨 ~10건 → Semaphore(10) → HikariCP 풀과 맞춤 → DB 과부하 방지
```

재시도·DLT 정책은 [doc4_etc.md](doc4_etc.md) `CheckoutDbGate 재시도` 절을 참고하세요.

### 3-4. Checkout 실패·예외 정리

| 상황 | HTTP | code / body | Redis | DB |
|------|------|-------------|-------|-----|
| 오픈 전 | **403** | `SALE_NOT_STARTED` | 미접촉 | 미접촉 |
| 매진 (Lua) | **200** | `success:false` | Lua만 | 미접촉 |
| 중복 예약 | **409** | `DUPLICATE_RESERVATION` | Lua만 | 미접촉 |
| Redis 장애 | **503** | `REDIS_UNAVAILABLE` | — | 미접촉 |
| 당첨 후 DB 일시 장애 | **200** | "예약에 실패하셨습니다." | 슬롯 **유지** + 재시도 | DLT 기록 |
| 당첨 후 DB 최종 포기 | **200** | "예약에 실패하셨습니다." | `release` | `booking_dead_letter` |
| UNIQUE 위반 (멱등) | **409** | `DUPLICATE_RESERVATION` | `release` + DB increase | 롤백 |

---

## 4. Booking 상세 흐름

### 4-1. 처리 순서

```mermaid
sequenceDiagram
    participant C as 클라이언트
    participant T as Tomcat
    participant BS as BookingService
    participant LK as OrderBookingLock
    participant VAL as PaymentCombinationValidator
    participant STR as PaymentStrategy[]
    participant DB as MySQL
    participant SG as StockGate

    C->>T: POST /api/bookings
    T->>BS: book(request)

    Note over BS,LK: ① 동일 orderNo 중복 결제 방지
    BS->>LK: SET NX lock:booking:{orderNo} TTL 30s
    alt 락 실패
        BS-->>C: 409 DUPLICATE_REQUEST
    end

    BS->>DB: Order 조회 (PENDING만 허용)
    BS->>VAL: 복합결제 조합 검증

    loop 결제 라인 순차 실행
        BS->>STR: strategy.pay()
        alt 성공
            BS->>DB: PaymentLine APPROVED
            opt YPOINT
                BS->>DB: PointHistory USE
            end
        else 실패
            Note over BS,SG: 보상 트랜잭션 fail()
            BS->>STR: strategy.cancel() (역순)
            BS->>DB: line.cancel, order.cancel, payment.cancel
            BS->>SG: release + stock.increase
            BS-->>C: 402 PAYMENT_FAILED
        end
    end

    BS->>DB: order.confirm() + payment.approve()
    BS->>LK: unlock.lua (토큰 검증 후 DEL)
    BS-->>C: 200 success
```

### 4-2. 결제 Strategy ([Strategy 패턴])

```mermaid
flowchart LR
    REQ[BookingRequest.paymentLines] --> VAL[PaymentCombinationValidator]
    VAL -->|primary 최대 1개| RES[PaymentStrategyResolver]

    RES --> CARD[CreditCardPaymentStrategy]
    RES --> YPAY[YpayPaymentStrategy]
    RES --> YPT[YpointPaymentStrategy]

    CARD -->|pay| PG[Mock PG 승인]
    YPAY -->|pay| PG
    YPT -->|pay| MEM[member.usePoints]

    CARD & YPAY -->|cancel| PG_REFUND["주석: PG 환불 API"]
    YPT -->|cancel| REFUND[refundPoints + PointHistory REFUND]
```

**복합결제 규칙**
- primary(카드·Y페이) **최대 1개** — 카드+Y페이 동시 사용 불가
- Y포인트 + 카드(또는 Y페이) 조합 허용
- 라인 합계 = 주문 금액

요청 body 예시는 [doc4_etc.md](doc4_etc.md) `복합결제 예시` 절을 참고하세요.

### 4-3. Booking 실패·예외 정리

| 상황 | HTTP | 비고 |
|------|------|------|
| 동시 결제 (같은 orderNo) | **409** | `DUPLICATE_REQUEST` |
| 주문 없음 | **404** | `ORDER_NOT_FOUND` |
| PENDING 아님 | **409** | `INVALID_ORDER_STATE` |
| 결제 조합 오류 | **422** | `INVALID_PAYMENT_COMBINATION` |
| 결제 실패 | **402** | `PAYMENT_FAILED` + 보상 실행 |
| Redis 장애 | **503** | `REDIS_UNAVAILABLE` |

---

## 5. 보상 트랜잭션 (결제 실패 시)

`BookingService.fail()` — **역순** 보상:

```mermaid
flowchart TB
    FAIL[결제 라인 N 실패] --> R1[역순 strategy.cancel]
    R1 --> R2[line.cancel → CANCELLED]
    R2 --> R3[stockGate.release Redis]
    R3 --> R4[stockRepository.increase DB]
    R4 --> R5[order.cancel → CANCELLED]
    R5 --> R6[payment.cancel → CANCELLED]
    R6 --> R7[XADD events:payment FAILED]
    R7 --> EX[PaymentFailedException throw]

    subgraph 보상내용
        R1 -.->|YPOINT| P1[포인트 환불 + REFUND 이력]
        R1 -.->|CARD/YPAY| P2[PG 환불 주석 no-op]
    end
```

| 시나리오 | succeededLines | 보상 내용 |
|----------|----------------|-----------|
| **첫 결제 실패** (포인트 부족) | 없음 | 재고·주문·결제만 취소, 포인트 변동 없음 |
| **두 번째 실패** (Y포인트 성공 → 카드 실패) | Y포인트 1건 | 포인트 환불 + USE/REFUND 이력 + 재고 복구 |

`@Transactional(noRollbackFor = PaymentFailedException.class)` — 예외가 나도 **보상 결과는 DB에 커밋**됩니다.

검증 테스트: `BookingCompensationIntegrationTest`

---

## 6. Redis vs MySQL 역할 분리

```mermaid
flowchart TB
    subgraph Redis역할["Redis — 빠른 판정·락"]
        R1[오픈 시각·가격 캐시]
        R2[재고 카운터 + 1인1예약 Set]
        R3[orderNo 분산 락]
        R4[감사 Stream — 처리 경로 아님]
    end

    subgraph MySQL역할["MySQL — 최종 진본"]
        M1[stock.remaining_stock CHECK]
        M2[orders UNIQUE member+product]
        M3[payment / payment_line 상태]
        M4[point_history 감사]
        M5[dead_letter 영구 기록]
    end

    R2 -->|당첨만| M1
    R2 -->|결제 실패| R2
```

**이중 안전망**
1. **Redis** — 폭주 흡수, ms 단위 fast-fail
2. **MySQL** — `UNIQUE`, 조건부 `UPDATE`, 트랜잭션으로 초과판매 차단

---

## 7. Audit Stream 역할 (처리 경로 아님)

| 시점 | `events:order` | `events:payment` | DLT |
|------|----------------|------------------|-----|
| Checkout 성공 | `PENDING` | — | — |
| Checkout DB 장애 | — | — | `dlt:booking` |
| Booking 성공 | — | `APPROVED` | — |
| Booking 실패 | — | `FAILED` | `dlt:payment` |

DB `orders.status`가 최종 진본이고, Stream은 **감사·구독용**입니다. Booking에서 `events:order`에 중복 기록하지 않습니다.

---

## 8. 00시 버스트 — pgExecutor 제거 (doc2)

```
구 방식:  pgExecutor(스레드 10 + 큐 200) → 1000 TPS 중 ~790건이 Redis 도달 전 503
현재:     Tomcat 동기 → 전 요청이 Redis Lua 통과 → 99% 매진/중복으로 즉시 종료
          통과 ~10건만 CheckoutDbGate → DB
```

| | 구 `pgExecutor` | 현재 |
|--|----------------|------|
| 버스트 처리 | 풀 상한에서 503 | **Redis Lua fast-fail** |
| DB 부하 | 모든 요청 경로 가능 | **당첨 ~10건만** |
| 복잡도 | CompletableFuture + 별도 풀 | **Tomcat 동기 단일 경로** |

**Redis lock** = 같은 `orderNo` 중복 결제 방지 (정합성)  
**pgExecutor** = 스레드 풀 분리 (인프라) → **제거됨, 대체 관계 아님**

---

## 9. 상태 전이 요약

```mermaid
stateDiagram-v2
    [*] --> PENDING: Checkout 성공
    PENDING --> CONFIRMED: Booking 결제 성공
    PENDING --> CANCELLED: Booking 결제 실패 (보상)
    CONFIRMED --> CANCELLED: 환불 시나리오 (확장)
```

| 엔티티 | 성공 경로 | 실패(보상) 경로 |
|--------|-----------|-----------------|
| `Order` | PENDING → **CONFIRMED** | PENDING → **CANCELLED** |
| `Payment` | PENDING → **APPROVED** | PENDING → **CANCELLED** |
| `PaymentLine` | **APPROVED** | APPROVED → **CANCELLED** |
| `stock` (Redis+DB) | -1 | +1 (release) |
| `PointHistory` | **USE** | **REFUND** (Y포인트만) |

---

## 10. 검증 테스트 매핑

| 테스트 | 검증 내용 |
|--------|-----------|
| `ConcurrentCheckoutIntegrationTest` | HTTP 응답·50명 동시 Checkout·재고 10개 |
| `DistributedStockConsistencyTest` | 앱 2대 시뮬레이션 1000동시·Redis+DB 정합성 |
| `CheckoutSaleNotStartedTest` | `Product.isSaleOpen()` false 시 재고 미접촉 |
| `BookingCompensationIntegrationTest` | 보상 트랜잭션 (포인트·재고·CANCELLED) |
| `PaymentCombinationValidatorTest` | 복합결제 규칙 |
| `YpointPaymentStrategyTest` | 포인트 pay/cancel |
| `ReservePayExceptionTest` | HTTP 상태·code API 계약 |

---

## 관련 문서

| 문서 | 내용 |
|------|------|
| [doc1.md](doc1.md) | Redis 키·Lua·Stream·Checkout/Booking Redis 상세 |
| [doc2.md](doc2.md) | 설계 원칙 4가지·pgExecutor 제거·아키텍처 단순화 |
| [doc4_etc.md](doc4_etc.md) | `stock_decr.lua` 단계별·`CheckoutDbGate` 재시도·복합결제 body 예시 |
| [API.md](API.md) | HTTP API 스펙 |
