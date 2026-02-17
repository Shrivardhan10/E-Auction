package com.eauction.service;

import com.eauction.model.entity.ExpertCertification;
import com.eauction.model.entity.Item;
import com.eauction.repository.ExpertCertificationRepository;
import com.eauction.repository.ItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates expert review workflow for PREMIUM items.
 * Runs asynchronously so the seller isn't blocked during upload.
 */
@Service
public class ExpertReviewService {

    private static final Logger log = LoggerFactory.getLogger(ExpertReviewService.class);

    private final GeminiAIService geminiAIService;
    private final ExpertCertificationRepository certificationRepository;
    private final ItemRepository itemRepository;
    private final ItemService itemService;

    public ExpertReviewService(GeminiAIService geminiAIService,
                               ExpertCertificationRepository certificationRepository,
                               ItemRepository itemRepository,
                               @org.springframework.context.annotation.Lazy ItemService itemService) {
        this.geminiAIService = geminiAIService;
        this.certificationRepository = certificationRepository;
        this.itemRepository = itemRepository;
        this.itemService = itemService;
    }

    /**
     * Run expert review asynchronously for a premium item.
     * Called in a separate thread so the upload response returns immediately.
     * Retries are handled inside GeminiAIService.
     */
    @Async
    public void reviewPremiumItem(Item item) {
        log.info("[ASYNC] Starting expert review for premium item: {} ({})", item.getName(), item.getItemId());

        // Use binary image data stored in the database
        byte[] imageData = item.getImageData();
        String contentType = item.getImageContentType();

        // Call Gemini AI with byte array (includes retry logic for 429)
        GeminiAIService.ReviewResult result = geminiAIService.reviewItem(
                item.getName(), item.getDescription(), imageData, contentType);

        log.info("[ASYNC] Expert review result for '{}': approved={}, score={}, grade={}, isError={}",
                item.getName(), result.approved(), result.score(), result.grade(), result.isError());

        // If API error occurred, keep item as PENDING — don't save bogus certification
        if (result.isError()) {
            log.warn("[ASYNC] AI review failed for '{}' due to API error. Item stays PENDING for retry.",
                    item.getName());
            Item freshItem = itemRepository.findById(item.getItemId()).orElse(item);
            freshItem.setAdminRemarks("AI review failed: " + result.explanation() + " — Use 'Retry Review' to try again.");
            itemRepository.save(freshItem);
            return;
        }

        // Persist certification (only for real AI evaluations)
        ExpertCertification cert = new ExpertCertification(
                item.getItemId(),
                "gemini-2.5-flash-lite",
                BigDecimal.valueOf(result.score()),
                result.grade(),
                result.explanation(),
                result.approved()
        );
        certificationRepository.save(cert);

        // Re-fetch item to get latest state, then update
        Item freshItem = itemRepository.findById(item.getItemId()).orElse(item);
        freshItem.setAdminStatus(result.approved() ? "APPROVED" : "REJECTED");
        freshItem.setAdminRemarks(result.explanation());
        itemRepository.save(freshItem);

        log.info("[ASYNC] Expert review completed for '{}': status={}", freshItem.getName(), freshItem.getAdminStatus());
    }

    /**
     * Retry review for an item. Deletes any existing failed certification first.
     */
    @Async
    public void retryReview(Item item) {
        log.info("[RETRY] Retrying expert review for item: {} ({})", item.getName(), item.getItemId());

        // Delete existing certification if any (from a previous failed attempt)
        certificationRepository.findByItemId(item.getItemId())
                .ifPresent(cert -> certificationRepository.delete(cert));

        // Reset item to PENDING
        item.setAdminStatus("PENDING");
        item.setAdminRemarks(null);
        itemRepository.save(item);

        // Re-run review
        reviewPremiumItem(item);
    }

    /**
     * Check if review is still in progress (item is PREMIUM + PENDING + no certification + no error remarks).
     * Returns false if the review failed (has error remarks), so the polling stops.
     */
    public boolean isReviewInProgress(Item item) {
        // Re-fetch from DB to get latest state
        Item fresh = itemRepository.findById(item.getItemId()).orElse(item);
        return "PREMIUM".equals(fresh.getItemType())
                && "PENDING".equals(fresh.getAdminStatus())
                && fresh.getAdminRemarks() == null  // no error remarks yet
                && certificationRepository.findByItemId(fresh.getItemId()).isEmpty();
    }

    /**
     * Check if an item's review failed (PREMIUM + PENDING + has error remark but no cert).
     */
    public boolean isReviewFailed(Item item) {
        return "PREMIUM".equals(item.getItemType())
                && "PENDING".equals(item.getAdminStatus())
                && item.getAdminRemarks() != null
                && item.getAdminRemarks().startsWith("AI review failed:")
                && certificationRepository.findByItemId(item.getItemId()).isEmpty();
    }

    /**
     * Get the certification for a given item, if any.
     */
    public Optional<ExpertCertification> getCertification(UUID itemId) {
        return certificationRepository.findByItemId(itemId);
    }
}
