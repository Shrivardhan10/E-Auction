package com.eauction.model.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "auctions")
public class Auction {

    @Id
    @Column(name = "auction_id")
    private UUID auctionId;

    @Column(name = "item_id", nullable = false, unique = true)
    private UUID itemId;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "min_increment_percent", nullable = false)
    private BigDecimal minIncrementPercent;

    @Column(name = "current_highest_bid")
    private BigDecimal currentHighestBid;

    @Column(name = "winner_id")
    private UUID winnerId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Auction() {}

    public UUID getAuctionId() {
        return auctionId;
    }

    public UUID getItemId() {
        return itemId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getMinIncrementPercent() {
        return minIncrementPercent;
    }

    public BigDecimal getCurrentHighestBid() {
        return currentHighestBid;
    }

    public UUID getWinnerId() {
        return winnerId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
