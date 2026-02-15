package com.eauction.controller;

import com.eauction.model.entity.Seller;
import com.eauction.service.SellerService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@Controller
@RequestMapping("/seller")
public class SellerAuthController {

    private final SellerService sellerService;

    public SellerAuthController(SellerService sellerService) {
        this.sellerService = sellerService;
    }

    @GetMapping("/login")
    public String loginPage() {
        return "seller/login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String email,
                        @RequestParam String password,
                        HttpSession session,
                        Model model) {

        Seller seller = sellerService.authenticate(email, password);

        if (seller != null) {
            session.setAttribute("sellerId", seller.getUserId().toString());
            session.setAttribute("sellerName", seller.getName());
            session.setAttribute("sellerEmail", seller.getEmail());
            return "redirect:/seller/dashboard";
        }

        model.addAttribute("error", "Invalid email or password");
        return "seller/login";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/seller/login";
    }
}

