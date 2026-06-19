# ReservePay API 설명서

Base URL: `http://localhost:8080` (로컬 IDE) / `http://localhost:${APP_HOST_PORT:-8081}` (Docker: 기본 app 1대, `make up-distributed` 시 Nginx LB → app1·app2)

모든 응답은 `application/json` 입니다. Checkout/Booking 모두 **동기 HTTP**로 처리되며,
00시 버스트는 Redis Lua에서 대부분 즉시 거절하고 DB는 당첨 소수만 접촉합니다
([DECISIONS.md](DECISIONS.md) 쟁점2 참고).

---

## 1. `GET /api/checkout` — 주문 생성 (재고 선점)

한정 수량 상품에 대해 재고를 원자적으로 선점하고 `PENDING` 상태의 주문을 생성합니다.

### Request

| 파라미터 | 위치 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| `productId` | query | long | Y | 구매할 상품 ID |
| `memberId` | query | long | Y | 구매자 회원 ID |

```bash
curl "http://localhost:8080/api/checkout?productId=1&memberId=1"
```

### Response `200 OK` — 성공

```json
{
  "orderNo": "4b575809-968a-4966-91af-f4b3e5f085c3",
  "status": "PENDING",
  "totalAmount": 100000,
  "success": true,
  "message": null
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `orderNo` | string\|null (UUID) | 외부에 노출되는 주문 번호. Booking 요청 시 그대로 사용. 실패 시 `null` |
| `status` | string\|null | 성공 시 `PENDING`, 실패 시 `null` |
| `totalAmount` | long | 성공 시 상품 가격, 실패 시 `0` |
| `success` | boolean | 예약 성공 여부 |
| `message` | string\|null | 실패 사유 (성공 시 `null`) |

### Response `200 OK` — 예약 실패 (매진 또는 재시도 소진)

재고가 실제로 없는 매진과, Redis/DB 일시 장애로 재시도(최대 5회, 총 6번 시도)까지 모두
실패한 경우 모두 **에러 상태 코드 대신 200으로 응답**합니다. 클라이언트가 할 수 있는
조치가 없는 실패이기 때문입니다(자세한 근거는 [DECISIONS.md](DECISIONS.md) 쟁점7 참고).
다만 `message`는 원인에 따라 다릅니다.

```json
// 진짜 매진 (재고가 실제로 0)
{ "orderNo": null, "status": null, "totalAmount": 0, "success": false, "message": "판매가 종료되었습니다." }

// Redis/DB 일시 장애로 재시도까지 모두 소진
{ "orderNo": null, "status": null, "totalAmount": 0, "success": false, "message": "예약에 실패하셨습니다." }
```

일시 장애로 재시도 중일 때는 Redis Slot(차감분)을 풀지 않고 든 채로 DB 단계만 재시도합니다
(다른 요청이 그 슬롯을 가져갈 수 없어 초과판매가 될 수 없습니다). 최초 실패 시 `dlt:booking`
(Redis Stream)에 1회만 실시간 로그를 남기고, 5회 재시도까지 모두 실패한 경우만
`booking_dead_letter`(DB)에 사유와 시도 횟수(`attempts=6`)가 영구 기록됩니다. 단순 매진은
둘 다 기록되지 않습니다.

### 에러 응답 (요청을 고쳐야 하는 경우만)

에러 응답 포맷은 공통입니다.

```json
{ "code": "PRODUCT_NOT_FOUND", "message": "상품을 찾을 수 없습니다. productId=999" }
```

| HTTP 상태 | `code` | 발생 조건 |
|---|---|---|
| 404 | `PRODUCT_NOT_FOUND` | 존재하지 않는 `productId` |
| 403 | `SALE_NOT_STARTED` | `checkin_opening_at` 이전 (00시 오픈 전). Redis 재고 미접촉 |

```json
{ "code": "SALE_NOT_STARTED", "message": "아직 판매 시간이 아닙니다." }
```

| 409 | `DUPLICATE_RESERVATION` | 같은 회원이 같은 상품을 이미 예약함 (1인 1예약) |
| 503 | `REDIS_UNAVAILABLE` | Redis 연결/시스템 오류 |

---

## 2. `POST /api/bookings` — 결제 확정

Checkout으로 생성한 `PENDING` 주문에 대해 결제 수단을 적용하고 주문을 확정합니다.

### Request

**Header**

| 이름 | 필수 | 설명 |
|---|---|---|
| `Content-Type` | Y | `application/json` |

**Body**

```json
{
  "orderNo": "4b575809-968a-4966-91af-f4b3e5f085c3",
  "memberId": 1,
  "paymentLines": [
    { "method": "CREDIT_CARD", "amount": 80000 },
    { "method": "YPOINT", "amount": 20000 }
  ]
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `orderNo` | string | Y | Checkout 응답으로 받은 주문 번호 |
| `memberId` | long | Y | 구매자 회원 ID (주문 생성 시 회원과 일치해야 함) |
| `paymentLines` | array | Y | 결제 라인 목록 (1개 이상) |
| `paymentLines[].method` | string | Y | `CREDIT_CARD` / `YPAY` / `YPOINT` 중 하나 |
| `paymentLines[].amount` | long | Y | 해당 라인의 결제 금액(원), 0보다 커야 함 |

**결제 라인 조합 규칙**
- `CREDIT_CARD`와 `YPAY`는 동시에 사용할 수 없습니다(최대 1개 = primary 결제 수단).
- `YPOINT`는 위 둘 중 하나와 함께 보조 수단으로 사용할 수 있습니다.
- `paymentLines`의 금액 합계는 주문의 `totalAmount`와 정확히 일치해야 합니다.

```bash
curl -X POST http://localhost:8080/api/bookings \
  -H "Content-Type: application/json" \
  -d '{
        "orderNo": "4b575809-968a-4966-91af-f4b3e5f085c3",
        "memberId": 1,
        "paymentLines": [
          { "method": "CREDIT_CARD", "amount": 80000 },
          { "method": "YPOINT", "amount": 20000 }
        ]
      }'
```

동일 `orderNo`에 대한 결제가 **동시에** 들어오면 서버가 Redis 분산 락(`lock:booking:{orderNo}`)으로
한 건만 처리하고 나머지는 `409 DUPLICATE_REQUEST`로 거절합니다. 처리가 끝나면 락을 해제합니다
([DECISIONS.md](DECISIONS.md) 쟁점3 참고).

### Response `200 OK` — 결제 확정 성공

```json
{ "orderNo": "4b575809-...", "status": "CONFIRMED", "success": true, "message": null }
```

### Response `402 Payment Required` — 결제 실패 (보상 처리 완료)

라인 중 하나라도 영구 실패(예: 포인트 잔액 부족)하면, 이미 성공한 라인은 역순으로 취소되고
재고는 자동으로 복구됩니다. 응답 자체는 `402`이며 본문으로 사유를 알려줍니다.

```json
{ "orderNo": "fa3dcdb9-...", "status": "FAILED", "success": false, "message": "포인트 잔액이 부족합니다." }
```

`message`가 없으면 기본값 `"결제에 실패했습니다."`가 내려갑니다.

> **DB vs API:** 결제 실패 보상 후 DB `orders.status`는 `CANCELLED`이지만, API 응답 `status`는 `FAILED`입니다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `orderNo` | string | 요청한 주문 번호 |
| `status` | string | `CONFIRMED`(성공) 또는 `FAILED`(실패) |
| `success` | boolean | 결제 성공 여부 |
| `message` | string\|null | 실패 사유 (성공 시 `null`) |

### 에러 응답

| HTTP 상태 | `code` | 발생 조건 |
|---|---|---|
| 404 | `ORDER_NOT_FOUND` | `orderNo` + `memberId` 조합에 해당하는 주문이 없음 |
| 409 | `INVALID_ORDER_STATE` | 주문이 이미 `PENDING`이 아님 (이미 확정/실패/취소됨) |
| 409 | `DUPLICATE_REQUEST` | 동일 `orderNo` 결제가 이미 처리 중 (Redis 분산 락 선점 실패) |
| 409 | `DUPLICATE_RESERVATION` | Redis/DB 상태 불일치로 인한 드문 충돌 (재시도 권장) |
| 422 | `INVALID_PAYMENT_COMBINATION` | 결제 라인 조합 규칙 위반 (카드+Y-pay 동시 사용, 금액 합 불일치 등) |
| 503 | `REDIS_UNAVAILABLE` | Redis 연결/시스템 오류 |

---

## 공통 에러 응답 포맷

```json
{ "code": "STRING_CONSTANT", "message": "사람이 읽을 수 있는 설명" }
```

## 시나리오 예시 — 전체 흐름

```bash
# 1. 주문 생성
ORDER_NO=$(curl -s "http://localhost:8080/api/checkout?productId=1&memberId=1" | python3 -c "import sys,json;print(json.load(sys.stdin)['orderNo'])")

# 2. 결제 확정
curl -X POST http://localhost:8080/api/bookings \
  -H "Content-Type: application/json" \
  -d "{\"orderNo\":\"$ORDER_NO\",\"memberId\":1,\"paymentLines\":[{\"method\":\"CREDIT_CARD\",\"amount\":100000}]}"
```

## 시드 데이터

`src/main/java/sql/schema.sql` 기준 (자세한 표는 [README.md](README.md#93-시드-데이터) 참고).

| 상품 ID | 재고 | 가격 | 판매 오픈 (`checkin_opening_at`) |
|---|---|---|---|
| 1 | 10 | 100,000원 | `2026-06-20 00:00:00` |

> 오픈 시각 이전에는 Checkout이 `403 SALE_NOT_STARTED`를 반환합니다. 로컬 테스트 시 시각을 확인하세요.

| 회원 ID | 포인트 잔액 |
|---|---|
| 1 | 50,000 |
| 2 | 0 |
| 3 | 100,000 |
