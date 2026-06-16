package com.example.reservepay.domain.stock;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stock {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "remaining_stock", nullable = false)
    private Integer remainingStock;

    @Version
    private Long version;

    public static Stock of(long productId, int remainingStock) {
        if (remainingStock < 0) {
            throw new IllegalArgumentException("잔여 재고는 0 이상이어야 합니다.");
        }

        Stock stock = new Stock();
        stock.productId = productId;
        stock.remainingStock = remainingStock;
        return stock;
    }

    public boolean isAvailable() {
        return remainingStock > 0;
    }

    public void decrease() {
        if (remainingStock <= 0) {
            throw new IllegalStateException("재고가 부족합니다.");
        }
        remainingStock--;
    }

    public void increase() {
        remainingStock++;
    }
}
