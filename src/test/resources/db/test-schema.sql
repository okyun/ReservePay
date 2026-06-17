-- =====================================================================
--  선착순 예약/결제 시스템 스키마 (MySQL 8.0 / InnoDB / utf8mb4)
--  주문·결제 도메인 중심. 회원 인증은 평가 범위 외이므로 최소 구성.
--
--  설계 원칙: 이중 안전망
--    - Redis = 폭주 흡수용 1차 게이트 (재고 차감 / 멱등성 / 큐)
--    - MySQL = 최종 방어선 (UNIQUE 제약 / CHECK / 트랜잭션)
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 회원 (가용 포인트 보유 주체)
-- ---------------------------------------------------------------------
CREATE TABLE member (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(50)  NOT NULL,
    point_balance BIGINT       NOT NULL DEFAULT 0 COMMENT '가용 포인트(원)',
    version       BIGINT       NOT NULL DEFAULT 0 COMMENT '낙관적 락',
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_member_point CHECK (point_balance >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------
-- 2. 상품 (숙소)
-- ---------------------------------------------------------------------
CREATE TABLE product (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(200) NOT NULL,
    price       BIGINT       NOT NULL COMMENT '판매가(원)',
    checkin_opening_at DATETIME(6) NOT NULL COMMENT '선착순 판매 시작(00시 오픈)',
    total_stock INT          NOT NULL COMMENT '한정 수량(원장)',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_product_price CHECK (price >= 0),
    CONSTRAINT ck_product_total CHECK (total_stock >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------
-- 3. 재고 (DB 최종 방어선)
--    확정 트랜잭션에서 조건부 차감:
--      UPDATE stock SET remaining_stock = remaining_stock - 1
--      WHERE product_id = ? AND remaining_stock > 0;
--    affected rows = 0 이면 매진 → 트랜잭션 롤백 (초과판매 차단)
--    CHECK 제약이 음수 방지의 마지막 보루.
-- ---------------------------------------------------------------------
CREATE TABLE stock (
    product_id      BIGINT      NOT NULL,
    remaining_stock INT         NOT NULL COMMENT 'DB 백스톱 잔여 재고',
    version         BIGINT      NOT NULL DEFAULT 0 COMMENT '낙관적 락',
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (product_id),
    CONSTRAINT fk_stock_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT ck_stock_remaining CHECK (remaining_stock >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------
-- 4. 주문
--    uk_orders_idem            : 멱등성 백스톱 (중복 결제 차단)
--    uk_orders_member_product  : 1인 1예약 + 사용자별 초과판매 차단
--    FK 없음 — 상품 삭제·회원 탈퇴 후에도 주문 이력 보존.
-- ---------------------------------------------------------------------
CREATE TABLE orders (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    order_no        VARCHAR(36) NOT NULL COMMENT '외부 노출 주문번호(ULID)',
    member_id       BIGINT      NOT NULL,
    product_id      BIGINT      NOT NULL,
    status          VARCHAR(20) NOT NULL COMMENT 'PENDING/CONFIRMED/FAILED/CANCELLED',
    total_amount    BIGINT      NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL COMMENT '멱등성 키(DB 백스톱)',
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_order_no (order_no),
    UNIQUE KEY uk_orders_idem (idempotency_key),
    UNIQUE KEY uk_orders_member_product (product_id, member_id),
    KEY idx_orders_member (member_id),
    CONSTRAINT ck_orders_amount CHECK (total_amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------
-- 5. 결제 (주문 1 : 결제 1)
-- ---------------------------------------------------------------------
CREATE TABLE payment (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    order_id     BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL COMMENT 'PENDING/APPROVED/FAILED/CANCELLED',
    total_amount BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_order (order_id),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------
-- 6. 결제 라인 (복합 결제 구성요소)
--    한 결제는 1~2개의 라인으로 구성.
--    "카드·Y페이 혼용 금지(primary 최대 1개)" 규칙은
--    교차 행 제약이라 DB로 표현하기 어려워 애플리케이션 검증기에서 보장.
-- ---------------------------------------------------------------------
CREATE TABLE payment_line (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    payment_id BIGINT       NOT NULL,
    method     VARCHAR(20)  NOT NULL COMMENT 'CREDIT_CARD/YPAY/YPOINT',
    amount     BIGINT       NOT NULL,
    pg_tx_id   VARCHAR(100) NULL     COMMENT 'PG 승인번호(포인트는 NULL)',
    status     VARCHAR(20)  NOT NULL COMMENT 'APPROVED/CANCELLED',
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_payment_line_payment (payment_id),
    CONSTRAINT fk_payment_line_payment FOREIGN KEY (payment_id) REFERENCES payment (id),
    CONSTRAINT ck_payment_line_amount CHECK (amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------
-- 7. 포인트 변동 이력 (감사 / 보상 추적)
--    FK 없음 — 회원 탈퇴 후에도 이력 보존.
-- ---------------------------------------------------------------------
CREATE TABLE point_history (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    member_id  BIGINT      NOT NULL,
    order_id   BIGINT      NULL,
    amount     BIGINT      NOT NULL COMMENT '음수=사용, 양수=적립/복구',
    type       VARCHAR(20) NOT NULL COMMENT 'USE/REFUND/EARN',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_point_history_member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE payment_dead_letter (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    order_no   VARCHAR(36)  NOT NULL,
    member_id  BIGINT       NOT NULL,
    method     VARCHAR(20)  NOT NULL COMMENT 'CREDIT_CARD/YPAY/YPOINT',
    amount     BIGINT       NOT NULL,
    reason     VARCHAR(255) NOT NULL,
    attempts   INT          NOT NULL COMMENT '최종 실패까지의 시도 횟수',
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_payment_dead_letter_order_no (order_no),
    KEY idx_payment_dead_letter_member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE booking_dead_letter (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    order_no   VARCHAR(36)  NOT NULL,
    product_id BIGINT       NOT NULL,
    member_id  BIGINT       NOT NULL,
    reason     VARCHAR(255) NOT NULL,
    attempts   INT          NOT NULL COMMENT '최종 실패까지의 시도 횟수',
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_booking_dead_letter_order_no (order_no),
    KEY idx_booking_dead_letter_member (member_id),
    KEY idx_booking_dead_letter_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- =====================================================================
--  초기 데이터 (코드 수정 없이 실행 / 테스트용)
-- =====================================================================
INSERT INTO product (id, name, price, checkin_opening_at, total_stock)
VALUES (1, '시그니처 8월 첫째주 주말 오션뷰 스위트 초특가', 100000,
        '2020-01-01 00:00:00', 10);

INSERT INTO stock (product_id, remaining_stock) VALUES (1, 10);

INSERT INTO member (id, name, point_balance) VALUES
    (1, '테스터1', 50000),
    (2, '테스터2', 0),
    (3, '테스터3', 100000);


INSERT INTO member (id, name, point_balance) VALUES
    (4, '동시성테스터4', 0),
    (5, '동시성테스터5', 0),
    (6, '동시성테스터6', 0),
    (7, '동시성테스터7', 0),
    (8, '동시성테스터8', 0),
    (9, '동시성테스터9', 0),
    (10, '동시성테스터10', 0),
    (11, '동시성테스터11', 0),
    (12, '동시성테스터12', 0),
    (13, '동시성테스터13', 0),
    (14, '동시성테스터14', 0),
    (15, '동시성테스터15', 0),
    (16, '동시성테스터16', 0),
    (17, '동시성테스터17', 0),
    (18, '동시성테스터18', 0),
    (19, '동시성테스터19', 0),
    (20, '동시성테스터20', 0),
    (21, '동시성테스터21', 0),
    (22, '동시성테스터22', 0),
    (23, '동시성테스터23', 0),
    (24, '동시성테스터24', 0),
    (25, '동시성테스터25', 0),
    (26, '동시성테스터26', 0),
    (27, '동시성테스터27', 0),
    (28, '동시성테스터28', 0),
    (29, '동시성테스터29', 0),
    (30, '동시성테스터30', 0),
    (31, '동시성테스터31', 0),
    (32, '동시성테스터32', 0),
    (33, '동시성테스터33', 0),
    (34, '동시성테스터34', 0),
    (35, '동시성테스터35', 0),
    (36, '동시성테스터36', 0),
    (37, '동시성테스터37', 0),
    (38, '동시성테스터38', 0),
    (39, '동시성테스터39', 0),
    (40, '동시성테스터40', 0),
    (41, '동시성테스터41', 0),
    (42, '동시성테스터42', 0),
    (43, '동시성테스터43', 0),
    (44, '동시성테스터44', 0),
    (45, '동시성테스터45', 0),
    (46, '동시성테스터46', 0),
    (47, '동시성테스터47', 0),
    (48, '동시성테스터48', 0),
    (49, '동시성테스터49', 0),
    (50, '동시성테스터50', 0),
    (51, '동시성테스터51', 0),
    (52, '동시성테스터52', 0),
    (53, '동시성테스터53', 0),
    (54, '동시성테스터54', 0),
    (55, '동시성테스터55', 0),
    (56, '동시성테스터56', 0),
    (57, '동시성테스터57', 0),
    (58, '동시성테스터58', 0),
    (59, '동시성테스터59', 0),
    (60, '동시성테스터60', 0),
    (61, '동시성테스터61', 0),
    (62, '동시성테스터62', 0),
    (63, '동시성테스터63', 0);
