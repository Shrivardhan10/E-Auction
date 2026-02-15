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

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Auction() {}

    @PrePersist
    protected void onCreate() {
        if (auctionId == null) auctionId = UUID.randomUUID();
        if (status == null) status = "PENDING";
        if (minIncrementPercent == null) minIncrementPercent = new BigDecimal("10.00");
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Compute live status based on current timestamp. */
    public String computeStatus() {
        LocalDateTime now = LocalDateTime.now();
        if ("CANCELLED".equals(status)) return "CANCELLED";
        if ("COMPLETED".equals(status)) return "COMPLETED";
        if (now.isBefore(startTime)) return "PENDING";
        if (now.isAfter(endTime)) return "COMPLETED";
        return "LIVE";
    }

    // Getters & Setters
    public UUID getAuctionId() { return auctionId; }
    public void setAuctionId(UUID auctionId) { this.auctionId = auctionId; }

    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getMinIncrementPercent() { return minIncrementPercent; }
    public void setMinIncrementPercent(BigDecimal minIncrementPercent) { this.minIncrementPercent = minIncrementPercent; }

    public BigDecimal getCurrentHighestBid() { return currentHighestBid; }
    public void setCurrentHighestBid(BigDecimal currentHighestBid) { this.currentHighestBid = currentHighestBid; }

    public UUID getWinnerId() { return winnerId; }
    public void setWinnerId(UUID winnerId) { this.winnerId = winnerId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
