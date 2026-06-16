package com.example.reservepay.domain.product;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private Long price;

    @Column(name = "checkin_at", nullable = false)
    private LocalDateTime checkinAt;

    @Column(name = "checkout_at", nullable = false)
    private LocalDateTime checkoutAt;

    @Column(name = "total_stock", nullable = false)
    private Integer totalStock;

    public static Product of(String name, long price,
                             LocalDateTime checkinAt, LocalDateTime checkoutAt,
                             int totalStock) {
        if (price < 0) {
            throw new IllegalArgumentException("판매가는 0 이상이어야 합니다.");
        }
        if (totalStock < 0) {
            throw new IllegalArgumentException("재고는 0 이상이어야 합니다.");
        }
        if (!checkoutAt.isAfter(checkinAt)) {
            throw new IllegalArgumentException("체크아웃은 체크인 이후여야 합니다.");
        }

        Product product = new Product();
        product.name = name;
        product.price = price;
        product.checkinAt = checkinAt;
        product.checkoutAt = checkoutAt;
        product.totalStock = totalStock;
        return product;
    }
}
