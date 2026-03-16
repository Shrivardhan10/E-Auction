package com.eauction.model.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "delivery_verifications")
public class DeliveryVerification {

    @Id
    @Column(name = "delivery_id")
    private UUID deliveryId;

    @Column(name = "auction_id", nullable = false, unique = true)
    private UUID auctionId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "pickup_image_url")
    private String pickupImageUrl;

    @Column(name = "pickup_image_data", columnDefinition = "BYTEA")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.BINARY)
    private byte[] pickupImageData;

    @Column(name = "pickup_image_content_type", length = 100)
    private String pickupImageContentType;

    @Column(name = "similarity_score", precision = 5, scale = 2)
    private BigDecimal similarityScore;

    @Column(name = "is_verified")
    private Boolean isVerified;

    @Column(name = "status", nullable = false, columnDefinition = "delivery_status")
    private String status;

    @Column(name = "picked_up_at")
    private LocalDateTime pickedUpAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public DeliveryVerification() {}

    @PrePersist
    protected void onCreate() {
        if (deliveryId == null) deliveryId = UUID.randomUUID();
        if (status == null) status = "PENDING_PICKUP";
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters

    public UUID getDeliveryId() { return deliveryId; }
    public void setDeliveryId(UUID deliveryId) { this.deliveryId = deliveryId; }

    public UUID getAuctionId() { return auctionId; }
    public void setAuctionId(UUID auctionId) { this.auctionId = auctionId; }

    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }

    public String getPickupImageUrl() { return pickupImageUrl; }
    public void setPickupImageUrl(String pickupImageUrl) { this.pickupImageUrl = pickupImageUrl; }

    public byte[] getPickupImageData() { return pickupImageData; }
    public void setPickupImageData(byte[] pickupImageData) { this.pickupImageData = pickupImageData; }

    public String getPickupImageContentType() { return pickupImageContentType; }
    public void setPickupImageContentType(String pickupImageContentType) { this.pickupImageContentType = pickupImageContentType; }

    public BigDecimal getSimilarityScore() { return similarityScore; }
    public void setSimilarityScore(BigDecimal similarityScore) { this.similarityScore = similarityScore; }

    public Boolean getIsVerified() { return isVerified; }
    public void setIsVerified(Boolean isVerified) { this.isVerified = isVerified; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getPickedUpAt() { return pickedUpAt; }
    public void setPickedUpAt(LocalDateTime pickedUpAt) { this.pickedUpAt = pickedUpAt; }

    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(LocalDateTime deliveredAt) { this.deliveredAt = deliveredAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
