package com.example.reservepay.domain.paymentLine;

import com.example.reservepay.domain.payment.status.PaymentMethod;
import com.example.reservepay.domain.paymentLine.status.PaymentLineStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "pg_tx_id", length = 100)
    private String pgTxId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentLineStatus status;

    public static PaymentLine approved(long paymentId, PaymentMethod method,
                                       long amount, String pgTxId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("결제 라인 금액은 0보다 커야 합니다.");
        }
        if (method.isPoint() && pgTxId != null) {
            throw new IllegalArgumentException("포인트 결제에는 PG 승인번호가 없어야 합니다.");
        }
        if (method.isPrimary() && pgTxId == null) {
            throw new IllegalArgumentException("주 결제 수단에는 PG 승인번호가 필요합니다.");
        }

        PaymentLine line = new PaymentLine();
        line.paymentId = paymentId;
        line.method = method;
        line.amount = amount;
        line.pgTxId = pgTxId;
        line.status = PaymentLineStatus.APPROVED;
        return line;
    }

    public void cancel() {
        if (!status.canTransitionTo(PaymentLineStatus.CANCELLED)) {
            throw new IllegalStateException(
                    "결제 라인 상태를 " + status + "에서 CANCELLED로 변경할 수 없습니다.");
        }
        this.status = PaymentLineStatus.CANCELLED;
    }
}
