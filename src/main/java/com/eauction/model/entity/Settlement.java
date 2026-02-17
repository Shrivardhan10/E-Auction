package com.eauction.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "settlements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Settlement {

    @Id
    @GeneratedValue
    private UUID settlementId;

    @OneToOne
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal saleAmount;

    @Builder.Default
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal brokeragePercent = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal brokerageAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal sellerAmount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SettlementStatus status = SettlementStatus.PENDING;

    private LocalDateTime settledAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
