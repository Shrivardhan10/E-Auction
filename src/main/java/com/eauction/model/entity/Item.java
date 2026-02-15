package com.eauction.model.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "items")
public class Item {

    @Id
    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "image_url", nullable = false, columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "item_type", nullable = false)
    private String itemType;

    @Column(name = "base_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "admin_status")
    private String adminStatus;

    @Column(name = "admin_remarks", columnDefinition = "TEXT")
    private String adminRemarks;

    @Column(name = "reviewed_by", columnDefinition = "uuid")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Item() {}

    public Item(UUID itemId, UUID sellerId, String name, String description, 
                String imageUrl, String itemType, BigDecimal basePrice) {
        this.itemId = itemId != null ? itemId : UUID.randomUUID();
        this.sellerId = sellerId;
        this.name = name;
        this.description = description;
        this.imageUrl = imageUrl;
        this.itemType = itemType;
        this.basePrice = basePrice;
        this.adminStatus = "PENDING";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.itemId == null) this.itemId = UUID.randomUUID();
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.updatedAt == null) this.updatedAt = LocalDateTime.now();
        if (this.adminStatus == null) this.adminStatus = "PENDING";
    }

    // Getters and Setters
    public UUID getItemId() {
        return itemId;
    }

    public void setItemId(UUID itemId) {
        this.itemId = itemId;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public void setSellerId(UUID sellerId) {
        this.sellerId = sellerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public void setBasePrice(BigDecimal basePrice) {
        this.basePrice = basePrice;
    }

    public String getAdminStatus() {
        return adminStatus;
    }

    public void setAdminStatus(String adminStatus) {
        this.adminStatus = adminStatus;
    }

    public String getAdminRemarks() {
        return adminRemarks;
    }

    public void setAdminRemarks(String adminRemarks) {
        this.adminRemarks = adminRemarks;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(UUID reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

