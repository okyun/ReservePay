package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.common.exception.ReservePayException;
import com.example.reservepay.domain.payment.status.PaymentMethod;
import com.example.reservepay.web.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [Strategy] 복합 결제 조합 검증 단위 테스트 */
class PaymentCombinationValidatorTest {

    private final PaymentCombinationValidator validator = new PaymentCombinationValidator();

    /** 카드+Y-pay 동시 사용: HTTP 422, code=INVALID_PAYMENT_COMBINATION */
    @Test
    void 카드와_Y페이_동시_사용은_거부된다() {
        // primary 결제 수단(카드·Y페이)이 2개 → "카드와 Y-pay는 동시에 사용할 수 없습니다."
        List<PaymentLineCommand> commands = List.of(
                new PaymentLineCommand(PaymentMethod.CREDIT_CARD, 50_000, "key"),
                new PaymentLineCommand(PaymentMethod.YPAY, 50_000, "key")
        );

        assertThatThrownBy(() -> validator.validate(commands, 100_000)) // primary 2개 → 거부
                .isInstanceOf(ReservePayException.class)
                .satisfies(ex -> assertHandlerResponse((ReservePayException) ex));
    }

    @Test
    void 카드와_포인트_복합결제는_허용된다() {
        List<PaymentLineCommand> commands = List.of(
                new PaymentLineCommand(PaymentMethod.CREDIT_CARD, 70_000, "key"), // primary 1개
                new PaymentLineCommand(PaymentMethod.YPOINT, 30_000, "key")      // 보조 포인트
        );

        assertThatCode(() -> validator.validate(commands, 100_000)).doesNotThrowAnyException(); // 7만+3만=10만
    }

    @Test
    void 카드만_단독으로_사용하면_허용된다() {
        List<PaymentLineCommand> commands = List.of(
                new PaymentLineCommand(PaymentMethod.CREDIT_CARD, 100_000, "key")
        );

        assertThatCode(() -> validator.validate(commands, 100_000)).doesNotThrowAnyException(); // 단일 라인·합계 일치
    }

    @Test
    void 포인트만_단독으로_사용하면_허용된다() {
        List<PaymentLineCommand> commands = List.of(
                new PaymentLineCommand(PaymentMethod.YPOINT, 100_000, "key") // primary 0개도 허용
        );

        assertThatCode(() -> validator.validate(commands, 100_000)).doesNotThrowAnyException();
    }

    @Test
    void 합계가_주문금액과_다르면_거부된다() {
        List<PaymentLineCommand> commands = List.of(
                new PaymentLineCommand(PaymentMethod.CREDIT_CARD, 50_000, "key")
        );

        assertThatThrownBy(() -> validator.validate(commands, 100_000)) // sum 5만 != total 10만
                .isInstanceOf(ReservePayException.class)
                .satisfies(ex -> assertHandlerResponse((ReservePayException) ex));
    }

    @Test
    void 금액이_0이하인_라인은_거부된다() {
        List<PaymentLineCommand> commands = List.of(
                new PaymentLineCommand(PaymentMethod.CREDIT_CARD, 0, "key") // amount <= 0
        );

        assertThatThrownBy(() -> validator.validate(commands, 0))
                .isInstanceOf(ReservePayException.class)
                .satisfies(ex -> assertHandlerResponse((ReservePayException) ex));
    }

    @Test
    void 빈_결제라인은_거부된다() {
        assertThatThrownBy(() -> validator.validate(List.of(), 0)) // paymentLines 비어 있음
                .isInstanceOf(ReservePayException.class)
                .satisfies(ex -> assertHandlerResponse((ReservePayException) ex));
    }

    private void assertHandlerResponse(ReservePayException ex) {
        ResponseEntity<?> response = ex.toResponseEntity(); // ExceptionAdvice 없이 예외 자체 검증

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY); // 422
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("INVALID_PAYMENT_COMBINATION");
        assertThat(body.message()).isEqualTo(ex.getMessage()); // 검증기 메시지 그대로
    }
}
