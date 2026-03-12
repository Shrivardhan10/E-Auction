package com.eauction.controller;

import com.eauction.dto.PlaceBidRequest;
import com.eauction.model.entity.Bid;
import com.eauction.service.RedisBidService;

import java.util.UUID;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bids")
public class BidController {

    private final RedisBidService redisBidService;

    public BidController(RedisBidService redisBidService) {
        this.redisBidService = redisBidService;
    }

    /**
     * Place a bid during live auction.
     * Bid is stored in Redis (not PostgreSQL) until auction ends.
     * Only the winning bid will be persisted to PostgreSQL after auction completion.
     */
    @PostMapping("/place")
    public Bid placeBid(@RequestBody PlaceBidRequest request) {
        return redisBidService.placeBid(request.getAuctionId(), request.getBidderId(), request.getBidAmount());
    }

    /**
     * Get the current highest bid from Redis.
     */
    @GetMapping("/highest/{auctionId}")
    public Bid getHighestBid(@PathVariable UUID auctionId) {
        // This returns the highest bid info - you may want to create a DTO for this
        // For now returning a simple response
        Bid tempBid = new Bid();
        tempBid.setAuctionId(auctionId);
        tempBid.setAmount(redisBidService.getCurrentHighestBid(auctionId));
        return tempBid;
    }

    /**
     * Get recent bids from Redis.
     * Note: These are temporary bids from the current live auction.
     */
    @GetMapping("/recent/{auctionId}")
    public List<String> getRecentBids(
            @PathVariable UUID auctionId,
            @RequestParam(defaultValue = "10") int count) {
        return redisBidService.getRecentBids(auctionId, count);
    }

    /**
     * Get total bid count from Redis.
     */
    @GetMapping("/count/{auctionId}")
    public long getBidCount(@PathVariable UUID auctionId) {
        return redisBidService.getBidCount(auctionId);
    }
}
