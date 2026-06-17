package com.example.reservepay.domain.payment.strategy;

public record PaymentLineOutcome(
        boolean success,
        String pgTxId,
        String message,
        boolean retryable
) {

    /**
     * PG 주 결제 수단(카드·Y페이) 성공 결과. {@code pgTxId}는 필수이다.
     */
    public static PaymentLineOutcome success(String pgTxId) {
        if (pgTxId == null || pgTxId.isBlank()) {
            throw new IllegalArgumentException("PG 승인번호가 필요합니다.");
        }
        return new PaymentLineOutcome(true, pgTxId, null, false);
    }

    /**
     * Y포인트 등 PG 승인번호가 없는 결제 수단의 성공 결과.
     */
    public static PaymentLineOutcome successWithoutPg() {
        return new PaymentLineOutcome(true, null, null, false);
    }

    /** 결제 실패(재시도 불가). BookingService가 PaymentFailedException으로 변환한다. */
    public static PaymentLineOutcome failure(String message) {
        return new PaymentLineOutcome(false, null, message, false);
    }

    /** 일시 실패(PG 타임아웃 등). BookingService가 제한 횟수만큼 재시도한다. */
    public static PaymentLineOutcome transientFailure(String message) {
        return new PaymentLineOutcome(false, null, message, true);
    }
}
