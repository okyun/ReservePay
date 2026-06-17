package com.example.reservepay.domain.paymentDead;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentDeadLetterRepository extends JpaRepository<PaymentDeadLetter, Long> {
}
