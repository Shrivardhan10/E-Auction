package com.eauction.controller;

import com.eauction.dto.RejectRequest;
import com.eauction.model.entity.Auction;
import com.eauction.model.entity.Item;
import com.eauction.model.entity.User;
import com.eauction.service.AdminService;
import com.eauction.service.AuctionService;
import com.eauction.service.SellerService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final SellerService sellerService;

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(required = false) String token,
                           HttpSession session, Model model) {
        User admin = getLoggedInAdmin(session, token);
        if (admin == null) return "redirect:/login";

        List<Item> pendingItems = adminService.getPendingItems();
        List<Item> allItems = adminService.getAllItems();
        long totalUsers = adminService.getTotalUserCount();
        BigDecimal revenue = adminService.getTotalBrokerage();

        model.addAttribute("user", admin);
        model.addAttribute("pendingItems", pendingItems.size());
        model.addAttribute("pendingItemsList", pendingItems);
        model.addAttribute("totalItems", allItems.size());
        model.addAttribute("approvedItems", allItems.stream().filter(i -> "APPROVED".equals(i.getAdminStatus())).count());
        model.addAttribute("rejectedItems", allItems.stream().filter(i -> "REJECTED".equals(i.getAdminStatus())).count());
        model.addAttribute("totalUsers", totalUsers);
        model.addAttribute("revenue", revenue != null ? revenue : BigDecimal.ZERO);
        model.addAttribute("token", token);

        // Users for activate/deactivate management
        model.addAttribute("usersList", adminService.getNonAdminUsers());

        return "admin/dashboard";
    }

    @GetMapping("/items/pending")
    @ResponseBody
    public List<Item> getPendingItems() {
        return adminService.getPendingItems();
    }

    @PutMapping("/items/{itemId}/approve")
    @ResponseBody
    public ResponseEntity<String> approveItem(@PathVariable UUID itemId) {
        adminService.approveItem(itemId);
        return ResponseEntity.ok("Item approved");
    }

    @PostMapping("/items/{itemId}/approve")
    public String approveItemForm(@PathVariable UUID itemId,
                                  @RequestParam(required = false) String token,
                                  HttpSession session,
                                  RedirectAttributes redirectAttributes) {
        User admin = getLoggedInAdmin(session, token);
        if (admin == null) return "redirect:/login";
        try {
            adminService.approveItem(itemId);
            redirectAttributes.addFlashAttribute("success", "Item approved successfully!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/dashboard?token=" + token;
    }

    @PutMapping("/items/{itemId}/reject")
    @ResponseBody
    public ResponseEntity<String> rejectItem(
            @PathVariable UUID itemId,
            @RequestBody RejectRequest request) {
        adminService.rejectItem(itemId, request.getRemarks());
        return ResponseEntity.ok("Item rejected");
    }

    @PostMapping("/items/{itemId}/reject")
    public String rejectItemForm(@PathVariable UUID itemId,
                                 @RequestParam String remarks,
                                 @RequestParam(required = false) String token,
                                 HttpSession session,
                                 RedirectAttributes redirectAttributes) {
        User admin = getLoggedInAdmin(session, token);
        if (admin == null) return "redirect:/login";
        try {
            adminService.rejectItem(itemId, remarks);
            redirectAttributes.addFlashAttribute("success", "Item rejected.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/dashboard?token=" + token;
    }

    @PutMapping("/users/{userId}/deactivate")
    @ResponseBody
    public ResponseEntity<String> deactivateUser(@PathVariable UUID userId) {
        adminService.deactivateUser(userId);
        return ResponseEntity.ok("User deactivated");
    }

    @PostMapping("/users/{userId}/toggle-status")
    public String toggleUserStatus(@PathVariable UUID userId,
                                   @RequestParam(required = false) String token,
                                   HttpSession session,
                                   RedirectAttributes redirectAttributes) {
        User admin = getLoggedInAdmin(session, token);
        if (admin == null) return "redirect:/login";
        try {
            adminService.toggleUserStatus(userId);
            redirectAttributes.addFlashAttribute("success", "User status updated successfully!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/dashboard?token=" + token;
    }

    @GetMapping("/revenue")
    @ResponseBody
    public BigDecimal getRevenue() {
        return adminService.getTotalBrokerage();
    }

    private User getLoggedInAdmin(HttpSession session, String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String userIdStr = (String) session.getAttribute("user_" + token);
        String role = (String) session.getAttribute("role_" + token);
        if (userIdStr == null || !"ADMIN".equals(role)) {
            return null;
        }
        try {
            UUID userId = UUID.fromString(userIdStr);
            return sellerService.findById(userId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
