package com.eauction.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Legacy seller auth routes — redirects to universal login.
 */
@Controller
@RequestMapping("/seller")
public class SellerAuthController {

    @GetMapping("/login")
    public String loginPage() {
        return "redirect:/login";
    }

    @GetMapping("/logout")
    public String logout() {
        return "redirect:/logout";
    }
}

