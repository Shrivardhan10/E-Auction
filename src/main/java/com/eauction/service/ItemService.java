package com.eauction.service;

import com.eauction.model.entity.Item;
import com.eauction.repository.ItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ItemService {

    private static final Logger log = LoggerFactory.getLogger(ItemService.class);
    private static final BigDecimal PREMIUM_THRESHOLD = new BigDecimal("10000");

    /** Absolute path to uploads directory inside project root */
    private final Path uploadDir;

    private final ItemRepository itemRepository;
    private final ExpertReviewService expertReviewService;

    public ItemService(ItemRepository itemRepository, ExpertReviewService expertReviewService,
                       @org.springframework.beans.factory.annotation.Value("${app.upload-dir:uploads}") String uploadDirName) {
        this.itemRepository = itemRepository;
        this.expertReviewService = expertReviewService;
        // Resolve upload directory relative to the project root (where the jar/pom lives)
        this.uploadDir = java.nio.file.Path.of(System.getProperty("user.dir"), uploadDirName).toAbsolutePath();
        log.info("Upload directory resolved to: {}", this.uploadDir);
    }

    /**
     * Create and persist a new item for the given seller.
     * Business rules:
     *   base_price > 10000 → PREMIUM → auto Expert AI Review
     *   base_price <= 10000 → NORMAL → admin_status PENDING
     */
    public Item createItem(String name,
                           String description,
                           BigDecimal basePrice,
                           LocalDateTime auctionStartTime,
                           MultipartFile imageFile,
                           UUID sellerId) throws IOException {

        // Determine item type
        String itemType = basePrice.compareTo(PREMIUM_THRESHOLD) > 0 ? "PREMIUM" : "NORMAL";

        Item item = new Item();
        item.setItemId(UUID.randomUUID());
        item.setSellerId(sellerId);
        item.setName(name);
        item.setDescription(description);
        item.setItemType(itemType);
        item.setBasePrice(basePrice);
        item.setAdminStatus("PENDING");

        // Store image as binary data in the database
        if (imageFile != null && !imageFile.isEmpty()) {
            item.setImageData(imageFile.getBytes());
            item.setImageContentType(imageFile.getContentType());
            // Set imageUrl to the serving endpoint
            item.setImageUrl("/api/items/" + item.getItemId() + "/image");
        }

        Item savedItem = itemRepository.save(item);

        // If PREMIUM, trigger AI Expert Review in background thread
        if ("PREMIUM".equals(itemType)) {
            log.info("Premium item detected — triggering async Expert AI Review for '{}'", name);
            expertReviewService.reviewPremiumItem(savedItem);
        }

        return savedItem;
    }

    public List<Item> getItemsBySeller(UUID sellerId) {
        return itemRepository.findBySellerIdOrderByCreatedAtDesc(sellerId);
    }

    public List<Item> getApprovedItemsBySeller(UUID sellerId) {
        return itemRepository.findBySellerIdOrderByCreatedAtDesc(sellerId).stream()
                .filter(i -> "APPROVED".equals(i.getAdminStatus()))
                .toList();
    }

    public Item getItemById(UUID itemId) {
        return itemRepository.findById(itemId).orElse(null);
    }

    /**
     * Save uploaded file to /uploads directory.
     * Returns the relative URL stored in the database.
     */
    private String saveImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return null;
        }

        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
        }

        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String storedName = UUID.randomUUID() + extension;

        Path filePath = uploadDir.resolve(storedName);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
        log.info("Image saved to: {}", filePath);

        return "/uploads/" + storedName;
    }

    /** Returns the absolute upload directory path (used by other services for image resolution). */
    public Path getUploadDir() {
        return uploadDir;
    }
}
