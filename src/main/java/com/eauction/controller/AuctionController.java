package com.eauction.controller;

import com.eauction.exception.InvalidBidException;
import com.eauction.model.entity.Auction;
import com.eauction.model.entity.Bid;
import com.eauction.model.entity.Item;
import com.eauction.model.entity.Payment;
import com.eauction.model.entity.User;
import com.eauction.repository.AuctionRepository;
import com.eauction.repository.BidRepository;
import com.eauction.repository.PaymentRepository;
import com.eauction.service.ItemService;
import com.eauction.service.RedisBidService;
import com.eauction.service.SellerService;
import jakarta.servlet.http.HttpSession;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Handles all auction-related pages and WebSocket messaging.
 */
@Controller
public class AuctionController {

    private final AuctionRepository auctionRepository;
    private final RedisBidService redisBidService;
    private final ItemService itemService;
    private final SellerService sellerService;
    private final PaymentRepository paymentRepository;
    private final BidRepository bidRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public AuctionController(AuctionRepository auctionRepository,
                             RedisBidService redisBidService,
                             ItemService itemService,
                             SellerService sellerService,
                             PaymentRepository paymentRepository,
                             BidRepository bidRepository,
                             SimpMessagingTemplate messagingTemplate) {
        this.auctionRepository = auctionRepository;
        this.redisBidService = redisBidService;
        this.itemService = itemService;
        this.sellerService = sellerService;
        this.paymentRepository = paymentRepository;
        this.bidRepository = bidRepository;
        this.messagingTemplate = messagingTemplate;
    }

    // ─────────────────────────────────────────────────────────────
    //  BIDDER: Auction listing + join
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/bidder/auctions")
    public String bidderAuctionList(HttpSession session, Model model) {
        User bidder = getLoggedInUser(session, "BIDDER");
        if (bidder == null) return "redirect:/login";

        List<Auction> liveAuctions = auctionRepository.findByStatus("LIVE");
        List<Auction> pendingAuctions = auctionRepository.findByStatus("PENDING");

        List<Map<String, Object>> liveList = enrichAuctions(liveAuctions);
        List<Map<String, Object>> pendingList = enrichAuctions(pendingAuctions);

        // Check for won auctions with pending payment
        List<Payment> myPendingPayments = paymentRepository.findAll().stream()
                .filter(p -> p.getBidderId().equals(bidder.getUserId())
                        && "GUARANTEE".equals(p.getPaymentType())
                        && "PENDING".equals(p.getStatus()))
                .toList();

        model.addAttribute("user", bidder);
        model.addAttribute("liveAuctions", liveList);
        model.addAttribute("pendingAuctions", pendingList);
        model.addAttribute("pendingPayments", myPendingPayments);
        return "bidder/auctions";
    }

    @GetMapping("/bidder/auction/{auctionId}")
    public String bidderAuctionRoom(@PathVariable UUID auctionId, HttpSession session, Model model) {
        User bidder = getLoggedInUser(session, "BIDDER");
        if (bidder == null) return "redirect:/login";

        Auction auction = auctionRepository.findById(auctionId).orElse(null);
        if (auction == null) return "redirect:/bidder/auctions";

        Item item = itemService.getItemById(auction.getItemId());

        // Get data from Redis if activated
        BigDecimal currentHighest = redisBidService.getCurrentHighestBid(auctionId);
        BigDecimal minimumBid = redisBidService.getMinimumBid(auctionId);
        long bidCount = redisBidService.getBidCount(auctionId);

        // Check if this bidder won and has pending payment
        Payment pendingPayment = null;
        if ("COMPLETED".equals(auction.getStatus())
                && auction.getWinnerId() != null
                && auction.getWinnerId().equals(bidder.getUserId())) {
            pendingPayment = paymentRepository
                    .findByAuctionIdAndBidderIdAndPaymentType(auctionId, bidder.getUserId(), "GUARANTEE")
                    .filter(p -> "PENDING".equals(p.getStatus()))
                    .orElse(null);
        }

        model.addAttribute("user", bidder);
        model.addAttribute("auction", auction);
        model.addAttribute("item", item);
        model.addAttribute("currentHighest", currentHighest);
        model.addAttribute("minimumBid", minimumBid);
        model.addAttribute("bidCount", bidCount);
        model.addAttribute("pendingPayment", pendingPayment);
        return "bidder/auction-room";
    }

    // ─────────────────────────────────────────────────────────────
    //  BIDDER: REST APIs for real-time data
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/api/auction/{auctionId}/state")
    @ResponseBody
    public Map<String, Object> getAuctionState(@PathVariable UUID auctionId) {
        Auction auction = auctionRepository.findById(auctionId).orElse(null);
        if (auction == null) return Map.of("error", "not_found");

        BigDecimal highest = redisBidService.getCurrentHighestBid(auctionId);
        BigDecimal minimum = redisBidService.getMinimumBid(auctionId);
        String highestBidder = redisBidService.getHighestBidder(auctionId);
        long bidCount = redisBidService.getBidCount(auctionId);

        // For completed auctions, Redis data may be gone — fall back to PostgreSQL
        if ("COMPLETED".equals(auction.getStatus())) {
            if (highest.compareTo(BigDecimal.ZERO) == 0 && auction.getCurrentHighestBid() != null) {
                highest = auction.getCurrentHighestBid();
                minimum = BigDecimal.ZERO;
            }
            if ((highestBidder == null || highestBidder.isEmpty()) && auction.getWinnerId() != null) {
                highestBidder = auction.getWinnerId().toString();
            }
            if (bidCount == 0) {
                bidCount = bidRepository.findByAuctionIdOrderByCreatedAtDesc(auctionId).size();
            }
        }

        // Resolve bidder name
        String bidderName = "";
        if (highestBidder != null && !highestBidder.isEmpty()) {
            try {
                User user = sellerService.findById(UUID.fromString(highestBidder));
                if (user != null) bidderName = user.getName();
            } catch (Exception ignored) {}
        }

        // 2nd highest bidder info (for notification)
        String secondBidderId = "";
        String secondBidderName = "";
        if ("COMPLETED".equals(auction.getStatus()) && auction.getWinnerId() != null) {
            List<Bid> bids = bidRepository.findByAuctionIdOrderByCreatedAtDesc(auctionId);
            bids.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));
            // Find 2nd unique bidder
            for (Bid bid : bids) {
                if (!bid.getBidderId().equals(auction.getWinnerId())) {
                    secondBidderId = bid.getBidderId().toString();
                    User u = sellerService.findById(bid.getBidderId());
                    if (u != null) secondBidderName = u.getName();
                    break;
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", auction.getStatus());
        result.put("currentHighest", highest.toPlainString());
        result.put("minimumBid", minimum.toPlainString());
        result.put("highestBidder", highestBidder != null ? highestBidder : "");
        result.put("highestBidderName", bidderName);
        result.put("bidCount", bidCount);
        result.put("endTime", auction.getEndTime().toString());
        result.put("secondBidderId", secondBidderId);
        result.put("secondBidderName", secondBidderName);
        if (auction.getWinnerId() != null) {
            result.put("winnerId", auction.getWinnerId().toString());
        }
        return result;
    }

    @GetMapping("/api/auction/{auctionId}/bids")
    @ResponseBody
    public List<Map<String, String>> getRecentBids(@PathVariable UUID auctionId,
                                                    @RequestParam(defaultValue = "20") int limit) {
        List<String> rawBids = redisBidService.getRecentBids(auctionId, limit);

        // Fallback to PostgreSQL for completed auctions where Redis bids are gone
        if (rawBids.isEmpty()) {
            List<Bid> dbBids = bidRepository.findByAuctionIdOrderByCreatedAtDesc(auctionId);
            List<Map<String, String>> result = new ArrayList<>();
            int count = 0;
            for (Bid bid : dbBids) {
                if (count >= limit) break;
                Map<String, String> parsed = new HashMap<>();
                parsed.put("bidderId", bid.getBidderId().toString());
                parsed.put("amount", bid.getAmount().toPlainString());
                parsed.put("ts", bid.getCreatedAt() != null ? bid.getCreatedAt().toString() : "");
                User user = sellerService.findById(bid.getBidderId());
                parsed.put("bidderName", user != null ? user.getName() : "Unknown");
                result.add(parsed);
                count++;
            }
            return result;
        }

        List<Map<String, String>> result = new ArrayList<>();
        for (String json : rawBids) {
            Map<String, String> parsed = new HashMap<>();
            try {
                // Simple JSON parsing
                String bidderId = extractJsonField(json, "bidderId");
                String amount = extractJsonField(json, "amount");
                String ts = extractJsonField(json, "ts");
                parsed.put("bidderId", bidderId);
                parsed.put("amount", amount);
                parsed.put("ts", ts);

                // Resolve name
                if (bidderId != null && !bidderId.isEmpty()) {
                    User user = sellerService.findById(UUID.fromString(bidderId));
                    parsed.put("bidderName", user != null ? user.getName() : "Unknown");
                }
            } catch (Exception e) {
                parsed.put("raw", json);
            }
            result.add(parsed);
        }
        return result;
    }

    @PostMapping("/api/auction/{auctionId}/bid")
    @ResponseBody
    public Map<String, Object> placeBidRest(@PathVariable UUID auctionId,
                                             @RequestBody Map<String, String> body,
                                             HttpSession session) {
        User bidder = getLoggedInUser(session, "BIDDER");
        if (bidder == null) return Map.of("error", "Unauthorized");

        try {
            BigDecimal amount = new BigDecimal(body.get("amount"));
            Bid bid = redisBidService.placeBid(auctionId, bidder.getUserId(), amount);

            // Resolve bidder name
            String bidderName = bidder.getName();

            // Calculate new minimum
            BigDecimal newMinimum = amount.multiply(new BigDecimal("1.10")).setScale(2, RoundingMode.CEILING);

            // Broadcast via WebSocket
            messagingTemplate.convertAndSend("/topic/auction/" + auctionId,
                    Map.of("type", "NEW_BID",
                            "amount", amount.toPlainString(),
                            "bidderId", bidder.getUserId().toString(),
                            "bidderName", bidderName,
                            "minimumBid", newMinimum.toPlainString(),
                            "bidCount", redisBidService.getBidCount(auctionId),
                            "timestamp", bid.getCreatedAt().toString()));

            return Map.of("success", true, "bidId", bid.getBidId().toString());
        } catch (InvalidBidException e) {
            return Map.of("error", e.getMessage());
        } catch (Exception e) {
            return Map.of("error", "Failed to place bid: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  BIDDER: Payment
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/bidder/payment/{auctionId}")
    public String paymentPage(@PathVariable UUID auctionId, HttpSession session, Model model) {
        User bidder = getLoggedInUser(session, "BIDDER");
        if (bidder == null) return "redirect:/login";

        Auction auction = auctionRepository.findById(auctionId).orElse(null);
        if (auction == null) return "redirect:/bidder/auctions";

        Payment payment = paymentRepository
                .findByAuctionIdAndBidderIdAndPaymentType(auctionId, bidder.getUserId(), "GUARANTEE")
                .orElse(null);

        if (payment == null || !"PENDING".equals(payment.getStatus())) {
            return "redirect:/bidder/auctions";
        }

        Item item = itemService.getItemById(auction.getItemId());

        model.addAttribute("user", bidder);
        model.addAttribute("auction", auction);
        model.addAttribute("item", item);
        model.addAttribute("payment", payment);
        return "bidder/payment";
    }

    @PostMapping("/bidder/payment/{auctionId}/pay")
    @ResponseBody
    public Map<String, Object> processPayment(@PathVariable UUID auctionId, HttpSession session) {
        User bidder = getLoggedInUser(session, "BIDDER");
        if (bidder == null) return Map.of("error", "Unauthorized");

        Payment payment = paymentRepository
                .findByAuctionIdAndBidderIdAndPaymentType(auctionId, bidder.getUserId(), "GUARANTEE")
                .orElse(null);

        if (payment == null || !"PENDING".equals(payment.getStatus())) {
            return Map.of("error", "No pending payment found");
        }

        // Check deadline
        if (payment.getDueBy() != null && LocalDateTime.now().isAfter(payment.getDueBy())) {
            return Map.of("error", "Payment deadline has passed");
        }

        // Mark as paid (simple flow for testing)
        payment.setStatus("SUCCESS");
        payment.setPaidAt(LocalDateTime.now());
        paymentRepository.save(payment);

        // Clean up Redis for this auction
        redisBidService.deactivateAuction(auctionId);

        // Broadcast payment success
        messagingTemplate.convertAndSend("/topic/auction/" + auctionId,
                Map.of("type", "PAYMENT_COMPLETED",
                        "bidderId", bidder.getUserId().toString(),
                        "bidderName", bidder.getName()));

        return Map.of("success", true, "message", "Payment of ₹" + payment.getAmount().toPlainString() + " successful!");
    }

    // ─────────────────────────────────────────────────────────────
    //  SELLER: Auction Dashboard
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/seller/auction/{auctionId}")
    public String sellerAuctionDashboard(@PathVariable UUID auctionId, HttpSession session, Model model) {
        User seller = getLoggedInUser(session, "SELLER");
        if (seller == null) return "redirect:/login";

        Auction auction = auctionRepository.findById(auctionId).orElse(null);
        if (auction == null) return "redirect:/seller/items";

        Item item = itemService.getItemById(auction.getItemId());
        if (item == null || !item.getSellerId().equals(seller.getUserId())) {
            return "redirect:/seller/items";
        }

        populateAuctionDashboardModel(model, auction, item, auctionId);
        model.addAttribute("user", seller);
        return "seller/auction-dashboard";
    }

    // ─────────────────────────────────────────────────────────────
    //  ADMIN: Auction Monitor
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/admin/auctions")
    public String adminAuctionList(HttpSession session, Model model) {
        User admin = getLoggedInUser(session, "ADMIN");
        if (admin == null) return "redirect:/login";

        List<Auction> liveAuctions = auctionRepository.findByStatus("LIVE");
        List<Auction> completedAuctions = auctionRepository.findByStatus("COMPLETED");
        List<Auction> pendingAuctions = auctionRepository.findByStatus("PENDING");

        model.addAttribute("user", admin);
        model.addAttribute("liveAuctions", enrichAuctions(liveAuctions));
        model.addAttribute("completedAuctions", enrichAuctions(completedAuctions));
        model.addAttribute("pendingAuctions", enrichAuctions(pendingAuctions));
        return "admin/auctions";
    }

    @GetMapping("/admin/auction/{auctionId}")
    public String adminAuctionDashboard(@PathVariable UUID auctionId, HttpSession session, Model model) {
        User admin = getLoggedInUser(session, "ADMIN");
        if (admin == null) return "redirect:/login";

        Auction auction = auctionRepository.findById(auctionId).orElse(null);
        if (auction == null) return "redirect:/admin/auctions";

        Item item = itemService.getItemById(auction.getItemId());

        populateAuctionDashboardModel(model, auction, item, auctionId);
        model.addAttribute("user", admin);
        return "admin/auction-dashboard";
    }

    // ─────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────

    private List<Map<String, Object>> enrichAuctions(List<Auction> auctions) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Auction auction : auctions) {
            Item item = itemService.getItemById(auction.getItemId());
            Map<String, Object> map = new HashMap<>();
            map.put("auction", auction);
            map.put("item", item != null ? item : new Item());

            BigDecimal currentHighest = redisBidService.getCurrentHighestBid(auction.getAuctionId());
            long bidCount = redisBidService.getBidCount(auction.getAuctionId());
            
            // Initialize all fields to avoid Thymeleaf null pointer issues
            map.put("winnerName", null);
            map.put("sellerName", null);
            map.put("paymentStatus", null);

            // For completed auctions, Redis data may be gone — fall back to PostgreSQL
            if ("COMPLETED".equals(auction.getStatus())) {
                if (currentHighest.compareTo(BigDecimal.ZERO) == 0 && auction.getCurrentHighestBid() != null) {
                    currentHighest = auction.getCurrentHighestBid();
                }
                if (bidCount == 0) {
                    bidCount = bidRepository.findByAuctionIdOrderByCreatedAtDesc(auction.getAuctionId()).size();
                }
                // Winner info
                if (auction.getWinnerId() != null) {
                    User winner = sellerService.findById(auction.getWinnerId());
                    map.put("winnerName", winner != null ? winner.getName() : "Unknown");
                }
                // Payment status
                paymentRepository.findByAuctionIdAndBidderIdAndPaymentType(
                        auction.getAuctionId(),
                        auction.getWinnerId() != null ? auction.getWinnerId() : UUID.randomUUID(),
                        "GUARANTEE"
                ).ifPresent(payment -> map.put("paymentStatus", payment.getStatus()));
                // Seller info
                if (item != null && item.getSellerId() != null) {
                    User seller = sellerService.findById(item.getSellerId());
                    map.put("sellerName", seller != null ? seller.getName() : "Unknown");
                }
            }

            map.put("bidCount", bidCount);
            map.put("currentHighest", currentHighest);
            result.add(map);
        }
        return result;
    }

    /**
     * Shared helper: populate model for seller/admin auction-dashboard pages
     * with PostgreSQL fallback for completed auctions where Redis data is gone.
     */
    private void populateAuctionDashboardModel(Model model, Auction auction, Item item, UUID auctionId) {
        BigDecimal currentHighest = redisBidService.getCurrentHighestBid(auctionId);
        long bidCount = redisBidService.getBidCount(auctionId);
        String highestBidder = redisBidService.getHighestBidder(auctionId);

        // PostgreSQL fallback for completed auctions
        if ("COMPLETED".equals(auction.getStatus())) {
            if (currentHighest.compareTo(BigDecimal.ZERO) == 0 && auction.getCurrentHighestBid() != null) {
                currentHighest = auction.getCurrentHighestBid();
            }
            if (bidCount == 0) {
                bidCount = bidRepository.findByAuctionIdOrderByCreatedAtDesc(auctionId).size();
            }
            if ((highestBidder == null || highestBidder.isEmpty()) && auction.getWinnerId() != null) {
                highestBidder = auction.getWinnerId().toString();
            }
        }

        String highestBidderName = "";
        if (highestBidder != null && !highestBidder.isEmpty()) {
            try {
                User u = sellerService.findById(UUID.fromString(highestBidder));
                if (u != null) highestBidderName = u.getName();
            } catch (Exception ignored) {}
        }

        model.addAttribute("auction", auction);
        model.addAttribute("item", item);
        model.addAttribute("currentHighest", currentHighest);
        model.addAttribute("bidCount", bidCount);
        model.addAttribute("highestBidderName", highestBidderName);
    }

    private User getLoggedInUser(HttpSession session, String requiredRole) {
        // Try role-prefixed attribute first (multi-tab support)
        String userIdStr = (String) session.getAttribute(requiredRole + "_userId");
        if (userIdStr == null) {
            // Fallback to generic attrs
            userIdStr = (String) session.getAttribute("userId");
            String userRole = (String) session.getAttribute("userRole");
            if (userIdStr == null || !requiredRole.equals(userRole)) return null;
        }
        try {
            return sellerService.findById(UUID.fromString(userIdStr));
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonField(String json, String field) {
        String search = "\"" + field + "\":";
        int idx = json.indexOf(search);
        if (idx == -1) return "";
        idx += search.length();
        if (json.charAt(idx) == '"') {
            idx++;
            int end = json.indexOf("\"", idx);
            return end != -1 ? json.substring(idx, end) : "";
        } else {
            int end = json.indexOf(",", idx);
            if (end == -1) end = json.indexOf("}", idx);
            return end != -1 ? json.substring(idx, end).trim() : "";
        }
    }
}
