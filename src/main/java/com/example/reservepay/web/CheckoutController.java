package com.example.reservepay.web;

import com.example.reservepay.checkout.CheckoutService;
import com.example.reservepay.checkout.dto.CheckoutResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    /**
     * 매진/중복 등 대부분은 Redis Lua에서 즉시 끝나므로 Tomcat 스레드에서 동기 처리
     * DB는 당첨 소수만 CheckoutDbGate로 제한한다.
     */
    @GetMapping("/api/checkout")
    public CheckoutResponse checkout(@RequestParam long productId, @RequestParam long memberId) {
        return checkoutService.checkout(productId, memberId);
    }
}
