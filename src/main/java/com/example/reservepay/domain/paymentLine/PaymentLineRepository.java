package com.example.reservepay.domain.paymentLine;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentLineRepository extends JpaRepository<PaymentLine, Long> {

    List<PaymentLine> findByPaymentId(Long paymentId);
}
