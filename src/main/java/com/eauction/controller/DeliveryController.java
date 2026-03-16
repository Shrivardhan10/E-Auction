package com.eauction.controller;

import com.eauction.model.entity.DeliveryVerification;
import com.eauction.model.entity.User;
import com.eauction.service.DeliveryService;
import com.eauction.service.SellerService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/delivery")
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final SellerService sellerService;

    public DeliveryController(DeliveryService deliveryService, SellerService sellerService) {
        this.deliveryService = deliveryService;
        this.sellerService = sellerService;
    }

    // ── Dashboard ──────────────────────────────────────────────────────────

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(required = false) String token,
                            HttpSession session, Model model) {
        String resolvedToken = resolveDeliveryToken(session, token);
        User agent = getLoggedInAgent(session, resolvedToken);
        if (agent == null) return "redirect:/login";

        List<Map<String, Object>> available = deliveryService.getAvailableDeliveries();

        List<DeliveryVerification> myDeliveries = deliveryService.getAgentDeliveries(agent.getUserId());

        List<Map<String, Object>> pendingPickupList = myDeliveries.stream()
                .filter(d -> "PENDING_PICKUP".equals(d.getStatus()))
                .map(deliveryService::enrichDelivery)
                .collect(Collectors.toList());

        List<Map<String, Object>> verifiedList = myDeliveries.stream()
                .filter(d -> "VERIFIED".equals(d.getStatus()))
                .map(deliveryService::enrichDelivery)
                .collect(Collectors.toList());

        List<Map<String, Object>> completedList = myDeliveries.stream()
                .filter(d -> "DELIVERED".equals(d.getStatus()))
                .map(deliveryService::enrichDelivery)
                .collect(Collectors.toList());

        List<Map<String, Object>> rejectedList = myDeliveries.stream()
                .filter(d -> "REJECTED".equals(d.getStatus()))
                .map(deliveryService::enrichDelivery)
                .collect(Collectors.toList());

        int activeCount = pendingPickupList.size() + verifiedList.size();

        model.addAttribute("user", agent);
        model.addAttribute("availableDeliveries", available);
        model.addAttribute("pendingPickupDeliveries", pendingPickupList);
        model.addAttribute("verifiedDeliveries", verifiedList);
        model.addAttribute("completedDeliveries", completedList);
        model.addAttribute("rejectedDeliveries", rejectedList);
        model.addAttribute("totalDeliveries", myDeliveries.size());
        model.addAttribute("activeCount", activeCount);
        model.addAttribute("completedCount", completedList.size());
        model.addAttribute("rejectedCount", rejectedList.size());
        model.addAttribute("token", resolvedToken);

        return "delivery/dashboard";
    }

    // ── Accept Delivery ────────────────────────────────────────────────────

    @PostMapping("/accept/{auctionId}")
    public String acceptDelivery(@PathVariable UUID auctionId,
                                 @RequestParam(required = false) String token,
                                 HttpSession session,
                                 RedirectAttributes redirectAttributes) {
        User agent = getLoggedInAgent(session, token);
        if (agent == null) return "redirect:/login";

        try {
            deliveryService.acceptDelivery(auctionId, agent.getUserId());
            redirectAttributes.addFlashAttribute("success", "Delivery task accepted! Pick up the item from the seller.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }

        return "redirect:/delivery/dashboard?token=" + token;
    }

    // ── Pick Up + Upload Image & Verify (Combined) ───────────────────────

    @PostMapping("/upload/{deliveryId}")
    @ResponseBody
    public Map<String, Object> pickupAndVerify(@PathVariable UUID deliveryId,
                                                  @RequestParam("pickupImage") MultipartFile pickupImage,
                                                  @RequestParam(required = false) String token,
                                                  HttpSession session) {
        User agent = getLoggedInAgent(session, token);
        if (agent == null) return Map.of("error", "Unauthorized");

        try {
            if (pickupImage.isEmpty()) {
                return Map.of("error", "Please upload an image of the picked-up item");
            }
            Map<String, Object> result = deliveryService.pickupAndVerify(deliveryId, pickupImage, agent.getUserId());
            result.put("success", true);
            return result;
        } catch (Exception e) {
            return Map.of("error", e.getMessage(), "success", false);
        }
    }

    // ── Mark Item as Delivered ──────────────────────────────────────────────

    @PostMapping("/deliver/{deliveryId}")
    public String markDelivered(@PathVariable UUID deliveryId,
                                @RequestParam(required = false) String token,
                                HttpSession session,
                                RedirectAttributes redirectAttributes) {
        User agent = getLoggedInAgent(session, token);
        if (agent == null) return "redirect:/login";

        try {
            deliveryService.markDelivered(deliveryId, agent.getUserId());
            redirectAttributes.addFlashAttribute("success", "Item delivered successfully! Final payment has been created for the buyer.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }

        return "redirect:/delivery/dashboard?token=" + token;
    }

    // ── Serve pickup image ─────────────────────────────────────────────────

    @GetMapping("/{deliveryId}/pickup-image")
    public ResponseEntity<byte[]> getPickupImage(@PathVariable UUID deliveryId) {
        return deliveryService.getDeliveryById(deliveryId)
                .filter(d -> d.getPickupImageData() != null)
                .map(d -> {
                    HttpHeaders headers = new HttpHeaders();
                    String ct = d.getPickupImageContentType();
                    headers.setContentType(ct != null ? MediaType.parseMediaType(ct) : MediaType.IMAGE_JPEG);
                    headers.setCacheControl("public, max-age=86400");
                    return new ResponseEntity<>(d.getPickupImageData(), headers, HttpStatus.OK);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private User getLoggedInAgent(HttpSession session, String token) {
        String resolvedToken = resolveDeliveryToken(session, token);
        if (resolvedToken == null || resolvedToken.isEmpty()) return null;
        String userIdStr = (String) session.getAttribute("user_" + resolvedToken);
        String role = (String) session.getAttribute("role_" + resolvedToken);
        if (userIdStr == null || !"DELIVERY".equals(role)) return null;
        try {
            return sellerService.findById(UUID.fromString(userIdStr));
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveDeliveryToken(HttpSession session, String token) {
        if (token != null && !token.isBlank()) {
            String role = (String) session.getAttribute("role_" + token);
            String userId = (String) session.getAttribute("user_" + token);
            if ("DELIVERY".equals(role) && userId != null && !userId.isBlank()) {
                return token;
            }
        }

        Enumeration<String> names = session.getAttributeNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (!name.startsWith("role_")) continue;
            Object role = session.getAttribute(name);
            if (!"DELIVERY".equals(role)) continue;

            String fallbackToken = name.substring("role_".length());
            String userId = (String) session.getAttribute("user_" + fallbackToken);
            if (userId != null && !userId.isBlank()) {
                return fallbackToken;
            }
        }
        return null;
    }
}
