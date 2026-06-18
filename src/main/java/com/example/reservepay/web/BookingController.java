package com.example.reservepay.web;

import com.example.reservepay.booking.BookingService;
import com.example.reservepay.booking.dto.BookingRequest;
import com.example.reservepay.booking.dto.BookingResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping("/api/bookings")
    public BookingResponse book(@RequestBody BookingRequest request) {
        return bookingService.book(request);
    }
}
