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

    @Column(name = "checkin_opening_at", nullable = false)
    private LocalDateTime checkinOpeningAt;

    @Column(name = "total_stock", nullable = false)
    private Integer totalStock;

    public static Product of(String name, long price,
                             LocalDateTime checkinOpeningAt,
                             int totalStock) {
        if (price < 0) {
            throw new IllegalArgumentException("판매가는 0 이상이어야 합니다.");
        }
        if (totalStock < 0) {
            throw new IllegalArgumentException("재고는 0 이상이어야 합니다.");
        }

        Product product = new Product();
        product.name = name;
        product.price = price;
        product.checkinOpeningAt = checkinOpeningAt;
        product.totalStock = totalStock;
        return product;
    }

    public boolean isSaleOpen() {
        return isSaleOpenAt(checkinOpeningAt);
    }

    public static boolean isSaleOpenAt(LocalDateTime checkinOpeningAt) {
        return !LocalDateTime.now().isBefore(checkinOpeningAt);
    }

    public void updateCheckinOpeningAt(LocalDateTime checkinOpeningAt) {
        this.checkinOpeningAt = checkinOpeningAt;
    }
}
