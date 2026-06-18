-- 기존 DB 볼륨에 누락된 테이블만 추가한다. (DROP 없음, IF NOT EXISTS)
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS booking_dead_letter (
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
