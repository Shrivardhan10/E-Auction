package com.eauction.service;

import com.eauction.model.entity.*;
import com.eauction.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Core business logic for the Delivery-Agent workflow:
 *
 *  1.  List available deliveries  (auctions where 50 % GUARANTEE is paid, no delivery record yet)
 *  2.  Accept a delivery task                        → status = PENDING_PICKUP
 *  3.  Pick up item + upload image + verify           → status = VERIFIED / REJECTED
 *  4.  Mark item as delivered to buyer                → status = DELIVERED, FINAL payment created
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    private static final double SIMILARITY_THRESHOLD = 60.0;

    private final DeliveryVerificationRepository deliveryRepo;
    private final AuctionRepository auctionRepository;
    private final PaymentRepository paymentRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final SettlementRepository settlementRepository;
    private final ImageSimilarityService imageSimilarityService;

    public DeliveryService(DeliveryVerificationRepository deliveryRepo,
                           AuctionRepository auctionRepository,
                           PaymentRepository paymentRepository,
                           ItemRepository itemRepository,
                           UserRepository userRepository,
                           SettlementRepository settlementRepository,
                           ImageSimilarityService imageSimilarityService) {
        this.deliveryRepo = deliveryRepo;
        this.auctionRepository = auctionRepository;
        this.paymentRepository = paymentRepository;
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
        this.settlementRepository = settlementRepository;
        this.imageSimilarityService = imageSimilarityService;
    }

    // ────────────────────────────────────────────────────────────────────
    //  1. Available deliveries — guarantee paid, no delivery record yet
    // ────────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> getAvailableDeliveries() {
        List<Auction> completedAuctions = auctionRepository.findByStatus("COMPLETED");
        List<Map<String, Object>> available = new ArrayList<>();

        for (Auction auction : completedAuctions) {
            if (auction.getWinnerId() == null) continue;

            // Check guarantee payment is SUCCESS
            List<Payment> auctionPayments = paymentRepository.findByAuctionId(auction.getAuctionId());
            Optional<Payment> guarantee = auctionPayments.stream()
                    .filter(p -> "GUARANTEE".equals(p.getPaymentType())
                            && auction.getWinnerId().equals(p.getBidderId())
                            && "SUCCESS".equals(p.getStatus()))
                    .findFirst();
            if (guarantee.isEmpty()) continue;

            // No delivery record yet
            if (deliveryRepo.findByAuctionId(auction.getAuctionId()).isPresent()) continue;

            Item item = itemRepository.findById(auction.getItemId()).orElse(null);
            User seller = item != null ? userRepository.findById(item.getSellerId()).orElse(null) : null;
            User buyer = userRepository.findById(auction.getWinnerId()).orElse(null);

            Map<String, Object> entry = new HashMap<>();
            entry.put("auction", auction);
            entry.put("item", item);
            entry.put("seller", seller);
            entry.put("buyer", buyer);
            entry.put("guaranteeAmount", guarantee.get().getAmount());
            available.add(entry);
        }

        return available;
    }

    // ────────────────────────────────────────────────────────────────────
    //  2. Accept a delivery task
    // ────────────────────────────────────────────────────────────────────
    @Transactional
    public DeliveryVerification acceptDelivery(UUID auctionId, UUID agentId) {
        // Validate auction
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new RuntimeException("Auction not found"));
        if (!"COMPLETED".equals(auction.getStatus()) || auction.getWinnerId() == null)
            throw new RuntimeException("Auction is not eligible for delivery");

        // Prevent double-accept
        if (deliveryRepo.findByAuctionId(auctionId).isPresent())
            throw new RuntimeException("Delivery already assigned for this auction");

        // Ensure guarantee is paid
        paymentRepository.findByAuctionId(auctionId).stream()
                .filter(p -> "GUARANTEE".equals(p.getPaymentType())
                        && auction.getWinnerId().equals(p.getBidderId())
                        && "SUCCESS".equals(p.getStatus()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Guarantee payment not completed"));

        DeliveryVerification delivery = new DeliveryVerification();
        delivery.setDeliveryId(UUID.randomUUID());
        delivery.setAuctionId(auctionId);
        delivery.setAgentId(agentId);
        delivery.setStatus("PENDING_PICKUP");

        DeliveryVerification saved = deliveryRepo.save(delivery);
        log.info("Delivery accepted: delivery={}, auction={}, agent={}",
                saved.getDeliveryId(), auctionId, agentId);
        return saved;
    }

    // ────────────────────────────────────────────────────────────────────
    //  3. Pick up item + upload image & run verification (combined flow)
    // ────────────────────────────────────────────────────────────────────
    @Transactional
    public Map<String, Object> pickupAndVerify(UUID deliveryId, MultipartFile pickupImage, UUID agentId)
            throws IOException {

        DeliveryVerification delivery = deliveryRepo.findById(deliveryId)
                .orElseThrow(() -> new RuntimeException("Delivery not found"));

        if (!delivery.getAgentId().equals(agentId))
            throw new RuntimeException("Not your delivery task");

        if (!"PENDING_PICKUP".equals(delivery.getStatus()))
            throw new RuntimeException("Delivery is not in PENDING_PICKUP state");

        // Mark as picked up
        delivery.setPickedUpAt(LocalDateTime.now());

        // Save pickup image
        byte[] pickupBytes = pickupImage.getBytes();
        delivery.setPickupImageData(pickupBytes);
        delivery.setPickupImageContentType(pickupImage.getContentType());
        delivery.setPickupImageUrl("/api/delivery/" + deliveryId + "/pickup-image");

        // Get original item image
        Auction auction = auctionRepository.findById(delivery.getAuctionId())
                .orElseThrow(() -> new RuntimeException("Auction not found"));
        Item item = itemRepository.findById(auction.getItemId())
                .orElseThrow(() -> new RuntimeException("Item not found"));

        byte[] originalImage = item.getImageData();
        if (originalImage == null) {
            throw new RuntimeException("Seller's original item image is missing. Cannot verify.");
        }

        // Calculate similarity
        double similarity = imageSimilarityService.calculateSimilarity(originalImage, pickupBytes);
        delivery.setSimilarityScore(BigDecimal.valueOf(similarity).setScale(2, RoundingMode.HALF_UP));

        log.info("Image similarity for delivery {}: {}% (threshold: {}%)",
                deliveryId, String.format("%.2f", similarity), SIMILARITY_THRESHOLD);

        Map<String, Object> result = new HashMap<>();
        result.put("similarityScore", delivery.getSimilarityScore());
        result.put("threshold", SIMILARITY_THRESHOLD);

        if (similarity >= SIMILARITY_THRESHOLD) {
            // ── VERIFIED — image matches, ready for delivery ──
            delivery.setIsVerified(true);
            delivery.setStatus("VERIFIED");
            deliveryRepo.save(delivery);

            result.put("verified", true);
            result.put("message", "Item picked up & verified! Similarity: " +
                    delivery.getSimilarityScore() + "%. You can now deliver the item to the buyer.");

            log.info("Delivery {} picked up & VERIFIED with similarity {}%", deliveryId, delivery.getSimilarityScore());

        } else {
            // ── REJECTED — cancel auction, refund guarantee ──
            delivery.setIsVerified(false);
            delivery.setStatus("REJECTED");
            deliveryRepo.save(delivery);

            // Mark auction as cancelled
            auction.setStatus("CANCELLED");
            auctionRepository.save(auction);

            // Mark guarantee as FAILED (to indicate refund)
            paymentRepository.findByAuctionId(auction.getAuctionId()).stream()
                    .filter(p -> "GUARANTEE".equals(p.getPaymentType())
                            && auction.getWinnerId().equals(p.getBidderId()))
                    .findFirst()
                    .ifPresent(gp -> {
                        gp.setStatus("FAILED");
                        paymentRepository.save(gp);
                    });

            result.put("verified", false);
            result.put("message", "Item REJECTED! Similarity: " +
                    delivery.getSimilarityScore() + "% (below " + SIMILARITY_THRESHOLD +
                    "% threshold). Auction cancelled. Guarantee amount will be refunded to the bidder.");

            log.info("Delivery {} REJECTED. Auction {} cancelled. Guarantee refund initiated.",
                    deliveryId, auction.getAuctionId());
        }

        return result;
    }

    // ────────────────────────────────────────────────────────────────────
    //  4. Mark item as delivered to buyer & create FINAL payment
    // ────────────────────────────────────────────────────────────────────
    @Transactional
    public DeliveryVerification markDelivered(UUID deliveryId, UUID agentId) {
        DeliveryVerification delivery = deliveryRepo.findById(deliveryId)
                .orElseThrow(() -> new RuntimeException("Delivery not found"));

        if (!delivery.getAgentId().equals(agentId))
            throw new RuntimeException("Not your delivery task");

        if (!"VERIFIED".equals(delivery.getStatus()))
            throw new RuntimeException("Item must be verified before marking as delivered");

        delivery.setStatus("DELIVERED");
        delivery.setDeliveredAt(LocalDateTime.now());
        DeliveryVerification saved = deliveryRepo.save(delivery);

        // Create FINAL payment (remaining 50%) for bidder
        Auction auction = auctionRepository.findById(delivery.getAuctionId())
                .orElseThrow(() -> new RuntimeException("Auction not found"));

        BigDecimal totalBid = auction.getCurrentHighestBid();
        BigDecimal finalAmount = totalBid.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        Payment finalPayment = Payment.builder()
                .paymentId(UUID.randomUUID())
                .auctionId(auction.getAuctionId())
                .bidderId(auction.getWinnerId())
                .amount(finalAmount)
                .paymentType("FINAL")
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();
        paymentRepository.save(finalPayment);

        log.info("Delivery {} marked DELIVERED. Final payment of ₹{} created for bidder {}",
                deliveryId, finalAmount, auction.getWinnerId());
        return saved;
    }

    // ────────────────────────────────────────────────────────────────────
    //  Delivery detail enrichment  (for dashboard display)
    // ────────────────────────────────────────────────────────────────────
    public Map<String, Object> enrichDelivery(DeliveryVerification delivery) {
        Auction auction = auctionRepository.findById(delivery.getAuctionId()).orElse(null);
        Item item = auction != null ? itemRepository.findById(auction.getItemId()).orElse(null) : null;
        User seller = item != null ? userRepository.findById(item.getSellerId()).orElse(null) : null;
        User buyer = auction != null && auction.getWinnerId() != null
                ? userRepository.findById(auction.getWinnerId()).orElse(null) : null;

        Map<String, Object> enriched = new HashMap<>();
        enriched.put("delivery", delivery);
        enriched.put("auction", auction);
        enriched.put("item", item);
        enriched.put("seller", seller);
        enriched.put("buyer", buyer);
        return enriched;
    }

    // ────────────────────────────────────────────────────────────────────
    //  Queries for the dashboard
    // ────────────────────────────────────────────────────────────────────
    public List<DeliveryVerification> getAgentDeliveries(UUID agentId) {
        return deliveryRepo.findByAgentIdOrderByCreatedAtDesc(agentId);
    }

    public List<DeliveryVerification> getActiveDeliveries(UUID agentId) {
        return deliveryRepo.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
                .filter(d -> "PENDING_PICKUP".equals(d.getStatus())
                        || "VERIFIED".equals(d.getStatus()))
                .collect(java.util.stream.Collectors.toList());
    }

    public Optional<DeliveryVerification> getDeliveryById(UUID deliveryId) {
        return deliveryRepo.findById(deliveryId);
    }
}
