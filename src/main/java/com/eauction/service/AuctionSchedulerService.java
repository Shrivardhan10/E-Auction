package com.eauction.service;

import com.eauction.model.entity.Auction;
import com.eauction.model.entity.Payment;
import com.eauction.repository.AuctionRepository;
import com.eauction.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduled service that manages auction lifecycle:
 * - Activates PENDING auctions when start time arrives
 * - Completes LIVE auctions when end time passes
 * - Manages payment windows after auction ends
 */
@Service
public class AuctionSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(AuctionSchedulerService.class);
    private static final int PAYMENT_WINDOW_MINUTES = 5;

    private final AuctionRepository auctionRepository;
    private final RedisBidService redisBidService;
    private final PaymentRepository paymentRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public AuctionSchedulerService(AuctionRepository auctionRepository,
                                   RedisBidService redisBidService,
                                   PaymentRepository paymentRepository,
                                   SimpMessagingTemplate messagingTemplate) {
        this.auctionRepository = auctionRepository;
        this.redisBidService = redisBidService;
        this.paymentRepository = paymentRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Every 2 seconds, check for auctions that need state transitions.
     */
    @Scheduled(fixedRate = 2000)
    public void manageAuctionLifecycle() {
        activatePendingAuctions();
        completeLiveAuctions();
        checkPaymentTimeouts();
    }

    /**
     * Transition PENDING → LIVE when start time arrives.
     */
    private void activatePendingAuctions() {
        List<Auction> pending = auctionRepository.findByStatus("PENDING");
        LocalDateTime now = LocalDateTime.now();

        for (Auction auction : pending) {
            if (!now.isBefore(auction.getStartTime())) {
                auction.setStatus("LIVE");
                auctionRepository.save(auction);

                // Load into Redis
                redisBidService.activateAuction(auction);

                // Broadcast to all clients
                messagingTemplate.convertAndSend("/topic/auctions/updates",
                        Map.of("type", "AUCTION_STARTED",
                                "auctionId", auction.getAuctionId().toString(),
                                "itemId", auction.getItemId().toString()));

                log.info("Auction {} is now LIVE", auction.getAuctionId());
            }
        }
    }

    /**
     * Transition LIVE → COMPLETED when end time passes.
     * Creates payment record for the winner with 5-min deadline.
     */
    @Transactional
    private void completeLiveAuctions() {
        List<Auction> live = auctionRepository.findByStatus("LIVE");
        LocalDateTime now = LocalDateTime.now();

        for (Auction auction : live) {
            if (now.isAfter(auction.getEndTime())) {
                // Get winner from Redis
                BigDecimal highestBid = redisBidService.getCurrentHighestBid(auction.getAuctionId());
                String highestBidder = redisBidService.getHighestBidder(auction.getAuctionId());

                if (highestBid.compareTo(BigDecimal.ZERO) > 0
                        && highestBidder != null && !highestBidder.isEmpty()) {
                    UUID winnerId = UUID.fromString(highestBidder);
                    auction.setWinnerId(winnerId);
                    auction.setCurrentHighestBid(highestBid);
                    auction.setStatus("COMPLETED");
                    auctionRepository.save(auction);

                    // Create GUARANTEE payment (50%)
                    BigDecimal guaranteeAmount = highestBid.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                    Payment payment = Payment.builder()
                            .paymentId(UUID.randomUUID())
                            .auctionId(auction.getAuctionId())
                            .bidderId(winnerId)
                            .amount(guaranteeAmount)
                            .paymentType("GUARANTEE")
                            .status("PENDING")
                            .dueBy(now.plusMinutes(PAYMENT_WINDOW_MINUTES))
                            .createdAt(now)
                            .build();
                    paymentRepository.save(payment);

                    // Broadcast auction completion + payment window
                    messagingTemplate.convertAndSend("/topic/auction/" + auction.getAuctionId(),
                            Map.of("type", "AUCTION_ENDED",
                                    "winnerId", winnerId.toString(),
                                    "winningBid", highestBid.toPlainString(),
                                    "paymentAmount", guaranteeAmount.toPlainString(),
                                    "paymentDeadline", payment.getDueBy().toString()));

                    log.info("Auction {} completed. Winner: {}, Bid: {}", auction.getAuctionId(), winnerId, highestBid);
                } else {
                    // No bids — just complete
                    auction.setStatus("COMPLETED");
                    auctionRepository.save(auction);
                    redisBidService.deactivateAuction(auction.getAuctionId());

                    messagingTemplate.convertAndSend("/topic/auction/" + auction.getAuctionId(),
                            Map.of("type", "AUCTION_ENDED_NO_BIDS"));

                    log.info("Auction {} completed with no bids", auction.getAuctionId());
                }
            } else {
                // Make sure LIVE auctions are activated in Redis
                if (!redisBidService.isActivated(auction.getAuctionId())) {
                    redisBidService.activateAuction(auction);
                }
            }
        }
    }

    /**
     * Check for expired payment windows.
     * If winner didn't pay within 5 minutes, remove their bid and offer to next bidder.
     */
    @Transactional
    private void checkPaymentTimeouts() {
        List<Payment> pendingPayments = paymentRepository.findAll().stream()
                .filter(p -> "PENDING".equals(p.getStatus()) && "GUARANTEE".equals(p.getPaymentType()))
                .filter(p -> p.getDueBy() != null && LocalDateTime.now().isAfter(p.getDueBy()))
                .toList();

        for (Payment payment : pendingPayments) {
            payment.setStatus("FAILED");
            paymentRepository.save(payment);

            Auction auction = auctionRepository.findById(payment.getAuctionId()).orElse(null);
            if (auction == null) continue;

            log.info("Payment timeout for auction {}. Removing bid from bidder {}", auction.getAuctionId(), payment.getBidderId());

            // Remove the highest bid from Redis and recalculate
            BigDecimal newHighest = redisBidService.removeHighestBid(auction.getAuctionId());
            String newHighestBidder = redisBidService.getHighestBidder(auction.getAuctionId());

            if (newHighest.compareTo(BigDecimal.ZERO) > 0
                    && newHighestBidder != null && !newHighestBidder.isEmpty()) {
                // Offer to next bidder
                UUID newWinnerId = UUID.fromString(newHighestBidder);
                auction.setWinnerId(newWinnerId);
                auction.setCurrentHighestBid(newHighest);
                auctionRepository.save(auction);

                BigDecimal guaranteeAmount = newHighest.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                Payment newPayment = Payment.builder()
                        .paymentId(UUID.randomUUID())
                        .auctionId(auction.getAuctionId())
                        .bidderId(newWinnerId)
                        .amount(guaranteeAmount)
                        .paymentType("GUARANTEE")
                        .status("PENDING")
                        .dueBy(LocalDateTime.now().plusMinutes(PAYMENT_WINDOW_MINUTES))
                        .createdAt(LocalDateTime.now())
                        .build();
                paymentRepository.save(newPayment);

                // Broadcast to auction channel
                messagingTemplate.convertAndSend("/topic/auction/" + auction.getAuctionId(),
                        Map.of("type", "PAYMENT_FALLBACK",
                                "previousBidder", payment.getBidderId().toString(),
                                "newWinnerId", newWinnerId.toString(),
                                "newWinningBid", newHighest.toPlainString(),
                                "paymentAmount", guaranteeAmount.toPlainString(),
                                "paymentDeadline", newPayment.getDueBy().toString()));

                log.info("Payment fell back to bidder {} for auction {}", newWinnerId, auction.getAuctionId());
            } else {
                // No more bidders — auction ends with no winner
                auction.setWinnerId(null);
                auction.setCurrentHighestBid(null);
                auctionRepository.save(auction);
                redisBidService.deactivateAuction(auction.getAuctionId());

                messagingTemplate.convertAndSend("/topic/auction/" + auction.getAuctionId(),
                        Map.of("type", "AUCTION_NO_WINNER"));

                log.info("Auction {} has no remaining valid bidders", auction.getAuctionId());
            }
        }
    }
}
