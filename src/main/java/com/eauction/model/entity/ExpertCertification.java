package com.eauction.model.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "expert_certifications")
public class ExpertCertification {

    @Id
    @Column(name = "cert_id")
    private UUID certId;

    @Column(name = "item_id", nullable = false, unique = true)
    private UUID itemId;

    @Column(name = "llm_model", nullable = false, length = 100)
    private String llmModel;

    @Column(name = "llm_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal llmScore;

    @Column(nullable = false, length = 5)
    private String grade;

    @Column(columnDefinition = "TEXT")
    private String remarks;

    @Column(name = "is_certified", nullable = false)
    private Boolean isCertified;

    @Column(name = "certified_at", nullable = false)
    private LocalDateTime certifiedAt;

    public ExpertCertification() {}

    public ExpertCertification(UUID itemId, String llmModel, BigDecimal llmScore,
                               String grade, String remarks, boolean isCertified) {
        this.certId = UUID.randomUUID();
        this.itemId = itemId;
        this.llmModel = llmModel;
        this.llmScore = llmScore;
        this.grade = grade;
        this.remarks = remarks;
        this.isCertified = isCertified;
        this.certifiedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public UUID getCertId() { return certId; }
    public void setCertId(UUID certId) { this.certId = certId; }

    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }

    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }

    public BigDecimal getLlmScore() { return llmScore; }
    public void setLlmScore(BigDecimal llmScore) { this.llmScore = llmScore; }

    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }

    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }

    public Boolean getIsCertified() { return isCertified; }
    public void setIsCertified(Boolean isCertified) { this.isCertified = isCertified; }

    public LocalDateTime getCertifiedAt() { return certifiedAt; }
    public void setCertifiedAt(LocalDateTime certifiedAt) { this.certifiedAt = certifiedAt; }
}
