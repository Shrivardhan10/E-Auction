package com.eauction.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "auction_id", nullable = false)
    private UUID auctionId;

    @Column(name = "bidder_id", nullable = false)
    private UUID bidderId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_type", nullable = false)
    private String paymentType; // GUARANTEE or FINAL

    @Column(nullable = false)
    private String status; // PENDING, SUCCESS, FAILED

    @Column(name = "due_by")
    private LocalDateTime dueBy;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (paymentId == null) paymentId = UUID.randomUUID();
        if (status == null) status = "PENDING";
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
