package com.eauction.service;

import com.eauction.exception.InvalidBidException;
import com.eauction.model.entity.Auction;
import com.eauction.model.entity.Bid;
import com.eauction.model.entity.Item;
import com.eauction.repository.AuctionRepository;
import com.eauction.repository.ItemRepository;
import com.eauction.repository.BidRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-backed bidding service for live auctions.
 * Uses Lua scripts for atomic bid validation + placement.
 * Also persists bids to PostgreSQL for durability.
 */
@Service
public class RedisBidService {

    private static final Logger log = LoggerFactory.getLogger(RedisBidService.class);

    private final RedisTemplate<String, String> redis;
    private final BidRepository bidRepository;
    private final AuctionRepository auctionRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ItemRepository itemRepository;

    // Track which auctions are activated in Redis
    private final Set<UUID> activatedAuctions = ConcurrentHashMap.newKeySet();

    public RedisBidService(RedisTemplate<String, String> redis,
                           BidRepository bidRepository,
                           AuctionRepository auctionRepository,
                           SimpMessagingTemplate messagingTemplate,ItemRepository itemRepository) {
        this.redis = redis;
        this.bidRepository = bidRepository;
        this.auctionRepository = auctionRepository;
        this.messagingTemplate = messagingTemplate;
        this.itemRepository = itemRepository;
    }

    /**
     * Load auction state into Redis when it goes LIVE.
     */
    public void activateAuction(Auction auction) {
        String stateKey = "auction:" + auction.getAuctionId() + ":state";
        String highestKey = "auction:" + auction.getAuctionId() + ":highest";

        // Get current highest from PostgreSQL (for auctions that may already have bids)
        BigDecimal currentHighest = auction.getCurrentHighestBid() != null
                ? auction.getCurrentHighestBid() : BigDecimal.ZERO;

        Map<String, String> state = new HashMap<>();
        state.put("status", "LIVE");
        state.put("itemId", auction.getItemId().toString());
        state.put("startTime", auction.getStartTime().toString());
        state.put("endTime", auction.getEndTime().toString());
        state.put("highestBid", currentHighest.toPlainString());
        state.put("highestBidder", auction.getWinnerId() != null ? auction.getWinnerId().toString() : "");

        redis.opsForHash().putAll(stateKey, state);

        // Set TTL: until auction ends + 1 hour
        long ttlSeconds = Duration.between(LocalDateTime.now(), auction.getEndTime().plusHours(1)).getSeconds();
        redis.expire(stateKey, Duration.ofSeconds(Math.max(ttlSeconds, 60)));

        redis.opsForValue().set(highestKey, currentHighest.toPlainString());
        redis.expire(highestKey, Duration.ofSeconds(Math.max(ttlSeconds, 60)));

        // Also load existing bids into sorted set
        String bidsKey = "auction:" + auction.getAuctionId() + ":bids";
        List<Bid> existingBids = bidRepository.findByAuctionIdOrderByCreatedAtDesc(auction.getAuctionId());
        for (Bid bid : existingBids) {
            String bidJson = String.format(
                    "{\"bidId\":\"%s\",\"bidderId\":\"%s\",\"amount\":%s,\"ts\":\"%s\"}",
                    bid.getBidId(), bid.getBidderId(), bid.getAmount().toPlainString(), bid.getCreatedAt()
            );
            redis.opsForZSet().add(bidsKey, bidJson, bid.getAmount().doubleValue());
        }
        redis.expire(bidsKey, Duration.ofSeconds(Math.max(ttlSeconds, 60)));

        activatedAuctions.add(auction.getAuctionId());
        log.info("Auction {} activated in Redis with highest bid {}", auction.getAuctionId(), currentHighest);
    }

    public boolean isActivated(UUID auctionId) {
        return activatedAuctions.contains(auctionId);
    }

    /**
     * Place a bid atomically via Redis Lua script.
     * Also persists to PostgreSQL.
     * Returns the bid result and broadcasts via WebSocket.
     */
    // public Bid placeBid(UUID auctionId, UUID bidderId, BigDecimal amount) {
    //     String highestKey = "auction:" + auctionId + ":highest";
    //     String bidsKey = "auction:" + auctionId + ":bids";
    //     String stateKey = "auction:" + auctionId + ":state";

    //     // Check auction is LIVE in Redis
    //     String status = (String) redis.opsForHash().get(stateKey, "status");
    //     if (!"LIVE".equals(status)) {
    //         throw new InvalidBidException("Auction is not active");
    //     }

    //     // Check auction hasn't expired
    //     String endTimeStr = (String) redis.opsForHash().get(stateKey, "endTime");
    //     if (endTimeStr != null && LocalDateTime.parse(endTimeStr).isBefore(LocalDateTime.now())) {
    //         throw new InvalidBidException("Auction has ended");
    //     }

    //     // Self-outbid prevention: bidder cannot bid if they already hold the highest bid
    //     String currentHighestBidder = (String) redis.opsForHash().get(stateKey, "highestBidder");
    //     if (currentHighestBidder != null && currentHighestBidder.equals(bidderId.toString())) {
    //         throw new InvalidBidException("You already have the highest bid. Wait for another bidder to outbid you.");
    //     }

    //     // Atomic Lua script: validate 10% rule and set new highest
    //     String luaScript = """
    //         local currentHighest = tonumber(redis.call('GET', KEYS[1]) or '0')
    //         local newBid = tonumber(ARGV[1])
    //         local minimumRequired = currentHighest * 1.10

    //         if currentHighest > 0 and newBid < minimumRequired then
    //             return '-1:' .. string.format('%.2f', currentHighest) .. ':' .. string.format('%.2f', minimumRequired)
    //         end

    //         if currentHighest == 0 and newBid <= 0 then
    //             return '-2:Bid must be greater than zero'
    //         end

    //         redis.call('SET', KEYS[1], ARGV[1])
    //         redis.call('ZADD', KEYS[2], newBid, ARGV[2])
    //         redis.call('HSET', KEYS[3], 'highestBid', ARGV[1])
    //         redis.call('HSET', KEYS[3], 'highestBidder', ARGV[3])
    //         return '1'
    //         """;

    //     String bidId = UUID.randomUUID().toString();
    //     LocalDateTime now = LocalDateTime.now();
    //     String bidJson = String.format(
    //             "{\"bidId\":\"%s\",\"bidderId\":\"%s\",\"amount\":%s,\"ts\":\"%s\"}",
    //             bidId, bidderId, amount.toPlainString(), now
    //     );

    //     DefaultRedisScript<String> script = new DefaultRedisScript<>(luaScript, String.class);
    //     String result = redis.execute(script,
    //             List.of(highestKey, bidsKey, stateKey),
    //             amount.toPlainString(), bidJson, bidderId.toString()
    //     );

    //     if (result != null && result.startsWith("-1:")) {
    //         String[] parts = result.split(":", 3);
    //         throw new InvalidBidException(
    //                 "Bid must be at least 10% higher than current highest bid of ₹" + parts[1] +
    //                 ". Minimum bid: ₹" + parts[2]
    //         );
    //     }
    //     if (result != null && result.startsWith("-2:")) {
    //         throw new InvalidBidException(result.substring(3));
    //     }

    //     // Persist to PostgreSQL as well
    //     Bid newBid = new Bid(auctionId, bidderId, amount);
    //     Bid savedBid = bidRepository.save(newBid);

    //     // Update auction's current highest in PostgreSQL
    //     Auction auction = auctionRepository.findById(auctionId).orElse(null);
    //     if (auction != null) {
    //         auction.setCurrentHighestBid(amount);
    //         auctionRepository.save(auction);
    //     }

    //     log.info("Bid placed: auction={}, bidder={}, amount={}", auctionId, bidderId, amount);
    //     return savedBid;
    // }
    public Bid placeBid(UUID auctionId, UUID bidderId, BigDecimal amount) {

    String highestKey = "auction:" + auctionId + ":highest";
    String bidsKey = "auction:" + auctionId + ":bids";
    String stateKey = "auction:" + auctionId + ":state";

    // -----------------------------
    // 1. Validate Auction State
    // -----------------------------

    String status = (String) redis.opsForHash().get(stateKey, "status");
    if (!"LIVE".equals(status)) {
        throw new InvalidBidException("Auction is not active");
    }

    String endTimeStr = (String) redis.opsForHash().get(stateKey, "endTime");
    if (endTimeStr != null &&
            LocalDateTime.parse(endTimeStr).isBefore(LocalDateTime.now())) {
        throw new InvalidBidException("Auction has ended");
    }

    // Prevent self-outbid
    String currentHighestBidder =
            (String) redis.opsForHash().get(stateKey, "highestBidder");

    if (currentHighestBidder != null &&
            currentHighestBidder.equals(bidderId.toString())) {
        throw new InvalidBidException(
                "You already have the highest bid. Wait for another bidder to outbid you."
        );
    }

    // -----------------------------
    // 2. Fetch Base Price (DB)
    // -----------------------------

    Auction auction = auctionRepository.findById(auctionId)
        .orElseThrow(() -> new InvalidBidException("Auction not found"));

Item item = itemRepository.findById(auction.getItemId())
        .orElseThrow(() -> new InvalidBidException("Item not found"));

BigDecimal basePrice = item.getBasePrice();



    // -----------------------------
    // 3. Atomic Lua Script
    // -----------------------------

    String luaScript = """
        local currentHighest = tonumber(redis.call('GET', KEYS[1]) or '0')
        local newBid = tonumber(ARGV[1])
        local basePrice = tonumber(ARGV[4])

        -- FIRST BID CASE
        if currentHighest == 0 then
            if newBid < basePrice then
                return '-3:' .. string.format('%.2f', basePrice)
            end
        else
            -- SUBSEQUENT BID CASE (10% RULE)
            local minimumRequired = currentHighest * 1.10
            if newBid < minimumRequired then
                return '-1:' .. string.format('%.2f', currentHighest)
                       .. ':' .. string.format('%.2f', minimumRequired)
            end
        end

        -- ACCEPT BID
        redis.call('SET', KEYS[1], ARGV[1])
        redis.call('ZADD', KEYS[2], newBid, ARGV[2])
        redis.call('HSET', KEYS[3], 'highestBid', ARGV[1])
        redis.call('HSET', KEYS[3], 'highestBidder', ARGV[3])

        return '1'
        """;

    String bidId = UUID.randomUUID().toString();
    LocalDateTime now = LocalDateTime.now();

    String bidJson = String.format(
            "{\"bidId\":\"%s\",\"bidderId\":\"%s\",\"amount\":%s,\"ts\":\"%s\"}",
            bidId,
            bidderId,
            amount.toPlainString(),
            now
    );

    DefaultRedisScript<String> script =
            new DefaultRedisScript<>(luaScript, String.class);

    // String result = redis.execute(
    //         script,
    //         List.of(highestKey, bidsKey, stateKey),
    //         amount.toPlainString(),
    //         bidJson,
    //         bidderId.toString(),
    //         basePrice.toPlainString()
    // );

    // // -----------------------------
    // // 4. Handle Script Errors
    // // -----------------------------

    // if (result != null && result.startsWith("-1:")) {
    //     String[] parts = result.split(":", 3);
    //     throw new InvalidBidException(
    //             "Bid must be at least 10% higher than current highest bid of ₹"
    //                     + parts[1] +
    //                     ". Minimum required: ₹" + parts[2]
    //     );
    // }

    // if (result != null && result.startsWith("-3:")) {
    //     String requiredBase = result.substring(3);
    //     throw new InvalidBidException(
    //             "First bid must be at least the base price of ₹" + requiredBase
    //     );
    // }
        String result = redis.execute(
            script,
            List.of(highestKey, bidsKey, stateKey),
            amount.toPlainString(),
            bidJson,
            bidderId.toString(),
            basePrice.toPlainString()
    );

    // -----------------------------
    // 4. Handle Script Errors
    // -----------------------------

    // ✅ Only accept if result is EXACTLY "1"
    if (!"1".equals(result)) {
        if (result != null && result.startsWith("-1:")) {
            String[] parts = result.split(":", 3);
            throw new InvalidBidException(
                    "Bid must be at least 10% higher than current highest bid of ₹"
                            + parts[1] +
                            ". Minimum required: ₹" + parts[2]
            );
        }

        if (result != null && result.startsWith("-3:")) {
            String requiredBase = result.substring(3);
            throw new InvalidBidException(
                    "First bid must be at least the base price of ₹" + requiredBase
            );
        }

        // Unexpected result from Lua script - reject bid
        throw new InvalidBidException("Bid validation failed: Redis returned " + result);
    }

    // Only reach here if result == "1" (bid accepted by Redis)

    // -----------------------------
    // 5. Persist to PostgreSQL
    // -----------------------------

    Bid newBid = new Bid(auctionId, bidderId, amount);
    Bid savedBid = bidRepository.save(newBid);

    auction.setCurrentHighestBid(amount);
    auctionRepository.save(auction);

    log.info("Bid placed: auction={}, bidder={}, amount={}",
            auctionId, bidderId, amount);

    return savedBid;
}


    /**
     * Get current highest bid from Redis.
     */
    public BigDecimal getCurrentHighestBid(UUID auctionId) {
        String val = redis.opsForValue().get("auction:" + auctionId + ":highest");
        return val != null && !val.isEmpty() ? new BigDecimal(val) : BigDecimal.ZERO;
    }

    /**
     * Get highest bidder UUID from Redis.
     */
    public String getHighestBidder(UUID auctionId) {
        return (String) redis.opsForHash().get("auction:" + auctionId + ":state", "highestBidder");
    }

    /**
     * Get recent bids from Redis sorted set (highest to lowest).
     */
    public List<String> getRecentBids(UUID auctionId, int count) {
        String key = "auction:" + auctionId + ":bids";
        Set<String> bids = redis.opsForZSet().reverseRange(key, 0, count - 1);
        return bids != null ? new ArrayList<>(bids) : List.of();
    }

    /**
     * Get total bid count from Redis.
     */
    public long getBidCount(UUID auctionId) {
        String key = "auction:" + auctionId + ":bids";
        Long count = redis.opsForZSet().zCard(key);
        return count != null ? count : 0;
    }

    /**
     * Remove the highest bid (used when winner fails to pay).
     * Returns the new highest bid amount, or ZERO if no bids remain.
     */
    public BigDecimal removeHighestBid(UUID auctionId) {
        String bidsKey = "auction:" + auctionId + ":bids";
        String highestKey = "auction:" + auctionId + ":highest";
        String stateKey = "auction:" + auctionId + ":state";

        // Remove the top-scored element
        Set<String> topBids = redis.opsForZSet().reverseRange(bidsKey, 0, 0);
        if (topBids != null && !topBids.isEmpty()) {
            redis.opsForZSet().remove(bidsKey, topBids.iterator().next());
        }

        // Get new highest
        Set<String> newTopBids = redis.opsForZSet().reverseRange(bidsKey, 0, 0);
        BigDecimal newHighest = BigDecimal.ZERO;
        String newHighestBidder = "";

        if (newTopBids != null && !newTopBids.isEmpty()) {
            String bidJson = newTopBids.iterator().next();
            // Parse amount from JSON
            try {
                int amtIdx = bidJson.indexOf("\"amount\":") + 9;
                int amtEnd = bidJson.indexOf(",", amtIdx);
                if (amtEnd == -1) amtEnd = bidJson.indexOf("}", amtIdx);
                newHighest = new BigDecimal(bidJson.substring(amtIdx, amtEnd));

                int bidderIdx = bidJson.indexOf("\"bidderId\":\"") + 12;
                int bidderEnd = bidJson.indexOf("\"", bidderIdx);
                newHighestBidder = bidJson.substring(bidderIdx, bidderEnd);
            } catch (Exception e) {
                log.error("Error parsing bid JSON: {}", bidJson, e);
            }
        }

        redis.opsForValue().set(highestKey, newHighest.toPlainString());
        redis.opsForHash().put(stateKey, "highestBid", newHighest.toPlainString());
        redis.opsForHash().put(stateKey, "highestBidder", newHighestBidder);

        return newHighest;
    }

    /**
     * Deactivate auction from Redis (on completion/cancellation).
     */
    public void deactivateAuction(UUID auctionId) {
        redis.delete("auction:" + auctionId + ":state");
        redis.delete("auction:" + auctionId + ":highest");
        redis.delete("auction:" + auctionId + ":bids");
        activatedAuctions.remove(auctionId);
        log.info("Auction {} deactivated from Redis", auctionId);
    }

    /**
     * Calculate minimum valid bid (10% above current highest).
     */
    public BigDecimal getMinimumBid(UUID auctionId) {
        BigDecimal highest = getCurrentHighestBid(auctionId);
        if (highest.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO; // first bid: any positive
        }
        return highest.multiply(new BigDecimal("1.10")).setScale(2, RoundingMode.CEILING);
    }
}
