package com.example.reservepay.web;

import com.example.reservepay.booking.dto.BookingResponse;
import com.example.reservepay.checkout.dto.CheckoutResponse;
import com.example.reservepay.common.exception.ReservePayException;
import com.example.reservepay.common.exception.booking.PaymentFailedException;
import com.example.reservepay.common.exception.checkout.SaleNotStartedException;
import com.example.reservepay.common.exception.checkout.SoldOutException;
import com.example.reservepay.domain.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReservePayException#toResponseEntity()} 가 API 계약(HTTP 상태·code·message)을 만족하는지 검증.
 * GlobalExceptionHandler 없이 예외 객체 자체의 응답 변환 로직을 단위 테스트한다.
 */
class ReservePayExceptionTest {

    /** 매진: HTTP 200 + success=false, message="판매가 종료되었습니다." */
    @Test
    void 매진은_200과_매진_본문으로_응답한다() {
        ResponseEntity<?> response = ReservePayException.soldOut(1L).toResponseEntity(); // SoldOutException → CheckoutResponse

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); // Checkout 매진은 4xx 아님
        CheckoutResponse body = (CheckoutResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo(SoldOutException.MESSAGE); // "판매가 종료되었습니다."
    }

    /** 결제 실패: HTTP 402 + status=FAILED, message=결제 실패 사유(예: 포인트 잔액 부족) */
    @Test
    void 결제_실패는_402와_실패_본문으로_응답한다() {
        ResponseEntity<?> response = ReservePayException.paymentFailed("order-1", "포인트 잔액이 부족합니다.")
                .toResponseEntity(); // PaymentFailedException → BookingResponse

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED); // HTTP 402
        BookingResponse body = (BookingResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.orderNo()).isEqualTo("order-1");
        assertThat(body.status()).isEqualTo(OrderStatus.FAILED); // DB는 CANCELLED, API는 FAILED
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo("포인트 잔액이 부족합니다.");
    }

    /** 결제 실패(메시지 null): HTTP 402, message="결제에 실패했습니다." (기본값) */
    @Test
    void 결제_실패_메시지가_없으면_기본_결제실패_메시지를_반환한다() {
        ResponseEntity<?> response = ReservePayException.paymentFailed("order-1", null).toResponseEntity(); // message null

        BookingResponse body = (BookingResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.message()).isEqualTo(PaymentFailedException.DEFAULT_MESSAGE); // "결제에 실패했습니다."
    }

    @Test
    void 상품_없음은_404로_응답한다() {
        ResponseEntity<?> response = ReservePayException.productNotFound(999L).toResponseEntity();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body.code()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void 판매_시작_전은_403으로_응답한다() {
        ResponseEntity<?> response = ReservePayException.saleNotStarted(
                java.time.LocalDateTime.of(2099, 1, 1, 0, 0)).toResponseEntity(); // 미래 오픈 시각

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body.code()).isEqualTo(SaleNotStartedException.CODE);
        assertThat(body.message()).isEqualTo(SaleNotStartedException.MESSAGE);
    }

    @Test
    void 주문_없음은_404로_응답한다() {
        ResponseEntity<?> response = ReservePayException.orderNotFound("order-1", 1L).toResponseEntity(); // orderNo+memberId 불일치

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body.code()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void 중복_예약은_409로_응답한다() {
        ResponseEntity<?> response = ReservePayException.duplicateReservation(1L, 2L).toResponseEntity(); // 1인 1예약

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body.code()).isEqualTo("DUPLICATE_RESERVATION");
    }

    @Test
    void 중복_결제_요청은_409로_응답한다() {
        ResponseEntity<?> response = ReservePayException.duplicateRequest("order-1").toResponseEntity(); // 분산 락 선점 실패

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body.code()).isEqualTo("DUPLICATE_REQUEST");
    }

    @Test
    void 잘못된_주문_상태는_409로_응답한다() {
        ResponseEntity<?> response = ReservePayException.invalidOrderState("order-1", "CONFIRMED")
                .toResponseEntity(); // PENDING이 아님

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body.code()).isEqualTo("INVALID_ORDER_STATE");
    }

    @Test
    void 잘못된_결제_조합은_422로_응답한다() {
        ResponseEntity<?> response = ReservePayException.invalidPaymentCombination("조합 오류").toResponseEntity();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body.code()).isEqualTo("INVALID_PAYMENT_COMBINATION");
    }

    @Test
    void ReservePayException_fallback은_400으로_응답한다() {
        ResponseEntity<?> response = ReservePayException.reservePayError("unknown").toResponseEntity(); // 전용 핸들러 없음

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body.code()).isEqualTo("RESERVEPAY_ERROR");
    }

    @Test
    void 회원_없음은_404로_응답한다() {
        ResponseEntity<?> response = ReservePayException.memberNotFound(999L).toResponseEntity();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body.code()).isEqualTo("MEMBER_NOT_FOUND");
    }

    @Test
    void 지원하지_않는_결제_수단은_422로_응답한다() {
        ResponseEntity<?> response = ReservePayException.unsupportedPaymentMethod(
                com.example.reservepay.domain.payment.status.PaymentMethod.CREDIT_CARD).toResponseEntity(); // Resolver에 전략 없을 때

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body.code()).isEqualTo("UNSUPPORTED_PAYMENT_METHOD");
    }
}
