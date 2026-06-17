package com.example.reservepay.domain.bookingDead;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingDeadLetterRepository extends JpaRepository<BookingDeadLetter, Long> {
}
