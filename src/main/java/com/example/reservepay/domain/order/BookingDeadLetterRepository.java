package com.example.reservepay.domain.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingDeadLetterRepository extends JpaRepository<BookingDeadLetter, Long> {
}
