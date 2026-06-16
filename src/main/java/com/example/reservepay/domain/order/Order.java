package com.example.reservepay.domain.order;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "orders", uniqueConstraints = {
        @UniqueConstraint(name = "uk_orders_idem", columnNames = "idempotency_key"),
        @UniqueConstraint(name = "uk_orders_member_product", columnNames = {"product_id", "member_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 36)
    private String orderNo;

    @Column(name = "member_id", nullable = false)
    private Long memberId;      // ID 참조 (연관관계 매핑 X)

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    public static Order pending(String orderNo, long memberId, long productId,
                                long totalAmount, String idempotencyKey) {
        Order o = new Order();
        o.orderNo = orderNo;
        o.memberId = memberId;
        o.productId = productId;
        o.status = OrderStatus.PENDING;
        o.totalAmount = totalAmount;
        o.idempotencyKey = idempotencyKey;
        return o;
    }

    public void confirm() { transitionTo(OrderStatus.CONFIRMED); }
    public void fail()    { transitionTo(OrderStatus.FAILED); }
    public void cancel()  { transitionTo(OrderStatus.CANCELLED); }

    private void transitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "주문 상태를 " + status + "에서 " + next + "(으)로 변경할 수 없습니다.");
        }
        this.status = next;
    }
}