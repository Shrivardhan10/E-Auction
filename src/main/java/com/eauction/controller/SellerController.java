package com.eauction.controller;

import com.eauction.model.entity.Auction;
import com.eauction.model.entity.ExpertCertification;
import com.eauction.model.entity.Item;
import com.eauction.model.entity.Seller;
import com.eauction.service.AuctionService;
import com.eauction.service.ExpertReviewService;
import com.eauction.service.GeminiAIService;
import com.eauction.service.ItemService;
import com.eauction.service.SellerService;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/seller")
public class SellerController {

    private final SellerService sellerService;
    private final ItemService itemService;
    private final ExpertReviewService expertReviewService;
    private final GeminiAIService geminiAIService;
    private final AuctionService auctionService;

    public SellerController(SellerService sellerService, ItemService itemService,
                            ExpertReviewService expertReviewService, GeminiAIService geminiAIService,
                            AuctionService auctionService) {
        this.sellerService = sellerService;
        this.itemService = itemService;
        this.expertReviewService = expertReviewService;
        this.geminiAIService = geminiAIService;
        this.auctionService = auctionService;
    }

    @GetMapping("/dashboard")
    public String dashboard(HttpSession session, Model model) {
        Seller seller = getLoggedInSeller(session);
        if (seller == null) return "redirect:/seller/login";

        List<Item> allItems = itemService.getItemsBySeller(seller.getUserId());
        List<Item> approvedItems = itemService.getApprovedItemsBySeller(seller.getUserId());
        
        long pendingItems = allItems.stream().filter(i -> "PENDING".equals(i.getAdminStatus())).count();
        long rejectedItems = allItems.stream().filter(i -> "REJECTED".equals(i.getAdminStatus())).count();

        model.addAttribute("seller", seller);
        model.addAttribute("totalItems", allItems.size());
        model.addAttribute("approvedItems", approvedItems.size());
        model.addAttribute("pendingItems", pendingItems);
        model.addAttribute("rejectedItems", rejectedItems);
        
        return "seller/dashboard";
    }

    @GetMapping("/items/upload")
    public String showUploadForm(HttpSession session) {
        if (getLoggedInSeller(session) == null) return "redirect:/seller/login";
        return "seller/upload-item";
    }

    @PostMapping("/items/upload")
    public String uploadItem(@RequestParam String name,
                             @RequestParam String description,
                             @RequestParam BigDecimal basePrice,
                             @RequestParam MultipartFile image,
                             HttpSession session,
                             RedirectAttributes redirectAttributes) {

        Seller seller = getLoggedInSeller(session);
        if (seller == null) return "redirect:/seller/login";

        try {
            if (image.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Please upload an image");
                return "redirect:/seller/items/upload";
            }

            Item item = itemService.createItem(name, description, basePrice, 
                    null, image, seller.getUserId());

            String typeLabel = basePrice.compareTo(new BigDecimal("10000")) > 0 ? "PREMIUM" : "NORMAL";

            if ("PREMIUM".equals(typeLabel)) {
                // Redirect to item detail so seller sees the live review progress
                redirectAttributes.addFlashAttribute("success",
                        "Item '" + item.getName() + "' uploaded as PREMIUM — AI Expert Review in progress...");
                return "redirect:/seller/items/" + item.getItemId();
            }

            redirectAttributes.addFlashAttribute("success",
                    "✓ Item '" + item.getName() + "' uploaded successfully as NORMAL (awaiting admin review)");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Upload failed: " + e.getMessage());
        }

        return "redirect:/seller/items";
    }

    @GetMapping("/items")
    public String listItems(HttpSession session, Model model) {
        Seller seller = getLoggedInSeller(session);
        if (seller == null) return "redirect:/seller/login";

        List<Item> items = itemService.getItemsBySeller(seller.getUserId());
        model.addAttribute("items", items);
        model.addAttribute("seller", seller);
        
        return "seller/my-items";
    }

    @GetMapping("/items/{itemId}")
    public String viewItem(@PathVariable UUID itemId, HttpSession session, Model model) {
        Seller seller = getLoggedInSeller(session);
        if (seller == null) return "redirect:/seller/login";

        Item item = itemService.getItemById(itemId);
        if (item == null || !item.getSellerId().equals(seller.getUserId())) {
            return "redirect:/seller/items";
        }

        model.addAttribute("item", item);
        model.addAttribute("seller", seller);

        // Check if AI review is in progress or failed
        boolean reviewInProgress = expertReviewService.isReviewInProgress(item);
        boolean reviewFailed = expertReviewService.isReviewFailed(item);
        model.addAttribute("reviewInProgress", reviewInProgress);
        model.addAttribute("reviewFailed", reviewFailed);

        // Add expert certification if exists (for premium items)
        expertReviewService.getCertification(itemId)
                .ifPresent(cert -> model.addAttribute("certification", cert));

        // Add auction data if exists (default to null so Thymeleaf can check)
        model.addAttribute("auction", null);
        auctionService.getAuctionByItemId(itemId).ifPresent(auction -> {
            auctionService.refreshStatus(auction); // update status based on current time
            model.addAttribute("auction", auction);
        });

        return "seller/item-detail";
    }

    /**
     * AJAX endpoint polled by item-detail page during AI review.
     * Returns JSON: { "status": "PENDING|APPROVED|REJECTED", "done": true/false }
     */
    @GetMapping("/items/{itemId}/review-status")
    @ResponseBody
    public Map<String, Object> reviewStatus(@PathVariable UUID itemId, HttpSession session) {
        Seller seller = getLoggedInSeller(session);
        if (seller == null) {
            return Map.of("error", "unauthorized", "done", true);
        }

        Item item = itemService.getItemById(itemId);
        if (item == null || !item.getSellerId().equals(seller.getUserId())) {
            return Map.of("error", "not_found", "done", true);
        }

        boolean inProgress = expertReviewService.isReviewInProgress(item);
        boolean reviewFailed = expertReviewService.isReviewFailed(item);
        return Map.of(
            "status", item.getAdminStatus(),
            "done", !inProgress,
            "failed", reviewFailed
        );
    }

    /**
     * Retry AI expert review for a premium item that previously failed.
     */
    @PostMapping("/items/{itemId}/retry-review")
    public String retryReview(@PathVariable UUID itemId, HttpSession session,
                              RedirectAttributes redirectAttributes) {
        Seller seller = getLoggedInSeller(session);
        if (seller == null) return "redirect:/seller/login";

        Item item = itemService.getItemById(itemId);
        if (item == null || !item.getSellerId().equals(seller.getUserId())) {
            return "redirect:/seller/items";
        }

        if (!"PREMIUM".equals(item.getItemType())) {
            redirectAttributes.addFlashAttribute("error", "Only PREMIUM items can have AI review.");
            return "redirect:/seller/items/" + itemId;
        }

        expertReviewService.retryReview(item);
        redirectAttributes.addFlashAttribute("success", "AI Expert Review restarted...");
        return "redirect:/seller/items/" + itemId;
    }

    /**
     * Quick diagnostic endpoint to test if the Gemini AI API is working.
     * GET /seller/test-ai → returns JSON with test result and timing.
     */
    @GetMapping("/test-ai")
    @ResponseBody
    public Map<String, Object> testAI() {
        long start = System.currentTimeMillis();
        try {
            GeminiAIService.ReviewResult result = geminiAIService.reviewItem(
                    "Test Item", "This is a test item to verify the AI API is working.", null);
            long elapsed = System.currentTimeMillis() - start;
            return Map.of(
                "status", result.isError() ? "ERROR" : "OK",
                "timeMs", elapsed,
                "model", "gemini-2.5-flash-lite",
                "approved", result.approved(),
                "score", result.score(),
                "grade", result.grade(),
                "explanation", result.explanation()
            );
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return Map.of("status", "ERROR", "timeMs", elapsed, "error", e.getMessage());
        }
    }

    /**
     * Push an APPROVED item to auction.
     * Seller provides start time, end time, and optional min increment percentage.
     */
    @PostMapping("/items/{itemId}/push-to-auction")
    public String pushToAuction(@PathVariable UUID itemId,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
                                @RequestParam(required = false, defaultValue = "10.00") BigDecimal minIncrementPercent,
                                HttpSession session,
                                RedirectAttributes redirectAttributes) {
        Seller seller = getLoggedInSeller(session);
        if (seller == null) return "redirect:/seller/login";

        Item item = itemService.getItemById(itemId);
        if (item == null || !item.getSellerId().equals(seller.getUserId())) {
            return "redirect:/seller/items";
        }

        if (!"APPROVED".equals(item.getAdminStatus())) {
            redirectAttributes.addFlashAttribute("error", "Only approved items can be pushed to auction.");
            return "redirect:/seller/items/" + itemId;
        }

        // Validate times
        if (endTime.isBefore(startTime) || endTime.equals(startTime)) {
            redirectAttributes.addFlashAttribute("error", "End time must be after start time.");
            return "redirect:/seller/items/" + itemId;
        }

        try {
            auctionService.createAuction(itemId, startTime, endTime, minIncrementPercent);
            redirectAttributes.addFlashAttribute("success",
                    "Item '" + item.getName() + "' has been pushed to auction!");
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }

        return "redirect:/seller/items/" + itemId;
    }

    private Seller getLoggedInSeller(HttpSession session) {
        String sellerIdStr = (String) session.getAttribute("sellerId");
        if (sellerIdStr == null) return null;
        try {
            UUID sellerId = UUID.fromString(sellerIdStr);
            return sellerService.findById(sellerId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

