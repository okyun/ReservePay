package com.example.reservepay.domain.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentDeadLetterRepository extends JpaRepository<PaymentDeadLetter, Long> {
}
