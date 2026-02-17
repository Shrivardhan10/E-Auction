package com.eauction.controller;

import com.eauction.model.entity.Auction;
import com.eauction.model.entity.Bid;
import com.eauction.model.entity.Item;
import com.eauction.model.entity.User;
import com.eauction.repository.AuctionRepository;
import com.eauction.repository.BidRepository;
import com.eauction.service.ItemService;
import com.eauction.service.SellerService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.*;

@Controller
@RequestMapping("/bidder")
public class BidderController {

    private final SellerService sellerService;
    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final ItemService itemService;

    public BidderController(SellerService sellerService,
                            AuctionRepository auctionRepository,
                            BidRepository bidRepository,
                            ItemService itemService) {
        this.sellerService = sellerService;
        this.auctionRepository = auctionRepository;
        this.bidRepository = bidRepository;
        this.itemService = itemService;
    }

    @GetMapping("/dashboard")
    public String dashboard(HttpSession session, Model model) {
        User bidder = getLoggedInBidder(session);
        if (bidder == null) return "redirect:/login";

        // Stats
        long liveAuctions = auctionRepository.countByStatus("LIVE");
        long totalBids = bidRepository.countByBidderId(bidder.getUserId());
        long auctionsWon = auctionRepository.countByWinnerId(bidder.getUserId());

        model.addAttribute("user", bidder);
        model.addAttribute("liveAuctions", liveAuctions);
        model.addAttribute("totalBids", totalBids);
        model.addAttribute("auctionsWon", auctionsWon);

        // Auctions Won — with item details, sale price
        List<Auction> wonAuctions = auctionRepository.findByWinnerId(bidder.getUserId());
        List<Map<String, Object>> wonList = wonAuctions.stream().map(auction -> {
            Item item = itemService.getItemById(auction.getItemId());
            Map<String, Object> m = new HashMap<>();
            m.put("auction", auction);
            m.put("item", item != null ? item : new Item());
            return m;
        }).toList();
        model.addAttribute("wonAuctionsList", wonList);

        // Auctions Participated — auctions where bidder placed bids (excluding won)
        List<Bid> allBids = bidRepository.findByBidderIdOrderByCreatedAtDesc(bidder.getUserId());
        Set<UUID> wonAuctionIds = new HashSet<>();
        for (Auction a : wonAuctions) wonAuctionIds.add(a.getAuctionId());

        Set<UUID> seenAuctionIds = new LinkedHashSet<>();
        for (Bid bid : allBids) {
            if (!wonAuctionIds.contains(bid.getAuctionId())) {
                seenAuctionIds.add(bid.getAuctionId());
            }
        }

        List<Map<String, Object>> participatedList = new ArrayList<>();
        for (UUID auctionId : seenAuctionIds) {
            auctionRepository.findById(auctionId).ifPresent(auction -> {
                Item item = itemService.getItemById(auction.getItemId());
                Map<String, Object> m = new HashMap<>();
                m.put("auction", auction);
                m.put("item", item != null ? item : new Item());
                // Get this bidder's highest bid in this auction
                Bid topBid = allBids.stream()
                        .filter(b -> b.getAuctionId().equals(auctionId))
                        .max(Comparator.comparing(Bid::getAmount))
                        .orElse(null);
                m.put("myHighestBid", topBid != null ? topBid.getAmount() : java.math.BigDecimal.ZERO);
                participatedList.add(m);
            });
        }
        model.addAttribute("participatedList", participatedList);

        // Recent bids by this bidder (last 10)
        List<Bid> recentBids = allBids.size() > 10 ? allBids.subList(0, 10) : allBids;
        model.addAttribute("recentBids", recentBids);

        return "bidder/dashboard";
    }

    private User getLoggedInBidder(HttpSession session) {
        // Try role-prefixed attribute first (multi-tab support)
        String userIdStr = (String) session.getAttribute("BIDDER_userId");
        if (userIdStr == null) {
            // Fallback to generic attrs
            userIdStr = (String) session.getAttribute("userId");
            String userRole = (String) session.getAttribute("userRole");
            if (userIdStr == null || !"BIDDER".equals(userRole)) return null;
        }
        try {
            UUID userId = UUID.fromString(userIdStr);
            return sellerService.findById(userId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
