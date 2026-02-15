package com.eauction.service;

import com.eauction.model.entity.Auction;
import com.eauction.model.entity.Item;
import com.eauction.repository.AuctionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages auction creation and status for approved items.
 */
@Service
public class AuctionService {

    private static final Logger log = LoggerFactory.getLogger(AuctionService.class);

    private final AuctionRepository auctionRepository;

    public AuctionService(AuctionRepository auctionRepository) {
        this.auctionRepository = auctionRepository;
    }

    /**
     * Create a new auction for an approved item.
     * Sets status based on current time vs start_time.
     */
    public Auction createAuction(UUID itemId, LocalDateTime startTime, LocalDateTime endTime,
                                  BigDecimal minIncrementPercent) {
        // Check if auction already exists for this item
        Optional<Auction> existing = auctionRepository.findByItemId(itemId);
        if (existing.isPresent()) {
            throw new IllegalStateException("An auction already exists for this item.");
        }

        Auction auction = new Auction();
        auction.setItemId(itemId);
        auction.setStartTime(startTime);
        auction.setEndTime(endTime);
        auction.setMinIncrementPercent(minIncrementPercent != null ? minIncrementPercent : new BigDecimal("10.00"));

        // Set initial status based on current time
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(startTime)) {
            auction.setStatus("PENDING");
        } else if (now.isAfter(endTime)) {
            auction.setStatus("COMPLETED");
        } else {
            auction.setStatus("LIVE");
        }

        Auction saved = auctionRepository.save(auction);
        log.info("Auction created: id={}, item={}, status={}, start={}, end={}",
                saved.getAuctionId(), itemId, saved.getStatus(), startTime, endTime);
        return saved;
    }

    /**
     * Get auction for a given item, if any.
     */
    public Optional<Auction> getAuctionByItemId(UUID itemId) {
        return auctionRepository.findByItemId(itemId);
    }

    /**
     * Update the auction status based on current timestamp.
     * Call this whenever you need the latest status.
     */
    public Auction refreshStatus(Auction auction) {
        String computed = auction.computeStatus();
        if (!computed.equals(auction.getStatus())) {
            auction.setStatus(computed);
            auctionRepository.save(auction);
            log.info("Auction {} status updated to {}", auction.getAuctionId(), computed);
        }
        return auction;
    }
}
