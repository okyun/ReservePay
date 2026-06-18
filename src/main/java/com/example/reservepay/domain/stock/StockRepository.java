package com.example.reservepay.domain.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockRepository extends JpaRepository<Stock, Long> {

    @Modifying
    @Query("UPDATE Stock s SET s.remainingStock = s.remainingStock - 1 " +
            "WHERE s.productId = :productId AND s.remainingStock > 0")
    int decreaseIfAvailable(@Param("productId") Long productId);

    @Modifying
    @Query("UPDATE Stock s SET s.remainingStock = s.remainingStock + 1 WHERE s.productId = :productId")
    int increase(@Param("productId") Long productId);
}
