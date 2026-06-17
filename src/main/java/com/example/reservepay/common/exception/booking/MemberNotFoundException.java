package com.example.reservepay.common.exception.booking;

import com.example.reservepay.common.exception.ReservePayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class MemberNotFoundException extends ReservePayException {

    public MemberNotFoundException(long memberId) {
        super("회원을 찾을 수 없습니다. memberId=" + memberId);
    }

    @Override
    public ResponseEntity<?> toResponseEntity() {
        return errorResponse(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND");
    }
}
