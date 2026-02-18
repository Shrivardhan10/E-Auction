package com.eauction.controller;

import com.eauction.model.entity.User;
import com.eauction.service.SellerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * Universal authentication controller for all user roles.
 * Handles login at /login and redirects to role-specific dashboards.
 */
@Controller
public class AuthController {

    private final SellerService sellerService;

    public AuthController(SellerService sellerService) {
        this.sellerService = sellerService;
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String email,
                        @RequestParam String password,
                        HttpServletRequest request,
                        Model model) {

        User user = sellerService.authenticate(email, password);

        if (user == null) {
            model.addAttribute("error", "Invalid email or password");
            return "login";
        }

        if (Boolean.FALSE.equals(user.getIsActive())) {
            model.addAttribute("error", "Your account has been deactivated. Contact admin.");
            return "login";
        }

        // Multi-tab support: generate unique token for each login session
        HttpSession session = request.getSession(true);

        // Generate unique token for this login (allows multiple logins per browser)
        String loginToken = UUID.randomUUID().toString();
        
        // Store user data with unique token key
        session.setAttribute("user_" + loginToken, user.getUserId().toString());
        session.setAttribute("role_" + loginToken, user.getRole());
        session.setAttribute("name_" + loginToken, user.getName());
        session.setAttribute("email_" + loginToken, user.getEmail());

        // Redirect based on role WITH token in URL
        return switch (user.getRole()) {
            case "SELLER" -> "redirect:/seller/dashboard?token=" + loginToken;
            case "ADMIN" -> "redirect:/admin/dashboard?token=" + loginToken;
            case "BIDDER" -> "redirect:/bidder/dashboard?token=" + loginToken;
            case "DELIVERY" -> {
                model.addAttribute("info", "Delivery dashboard is coming soon!");
                yield "login";
            }
            default -> {
                model.addAttribute("error", "Unknown user role");
                yield "login";
            }
        };
    }

    @GetMapping("/logout")
    public String logout(@RequestParam(required = false) String token, HttpSession session) {
        if (token != null) {
            // Only remove this specific login's data
            session.removeAttribute("user_" + token);
            session.removeAttribute("role_" + token);
            session.removeAttribute("name_" + token);
            session.removeAttribute("email_" + token);
        } else {
            session.invalidate();
        }
        return "redirect:/login";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String name,
                          @RequestParam String email,
                          @RequestParam String password,
                          @RequestParam String confirmPassword,
                          @RequestParam String phone,
                          @RequestParam String role,
                          @RequestParam(required = false) String address,
                          @RequestParam(required = false) String city,
                          @RequestParam(required = false) String state,
                          @RequestParam(required = false) String pincode,
                          Model model) {

        // Validate input
        if (name == null || name.trim().isEmpty()) {
            model.addAttribute("error", "Name is required");
            return "register";
        }
        if (email == null || !email.contains("@")) {
            model.addAttribute("error", "Valid email is required");
            return "register";
        }
        if (password == null || password.length() < 6) {
            model.addAttribute("error", "Password must be at least 6 characters");
            return "register";
        }
        if (!password.equals(confirmPassword)) {
            model.addAttribute("error", "Passwords do not match");
            return "register";
        }
        if (phone == null || phone.length() < 10) {
            model.addAttribute("error", "Valid 10+ digit phone number is required");
            return "register";
        }
        if (role == null || !role.matches("SELLER|BIDDER|DELIVERY")) {
            model.addAttribute("error", "Valid role is required");
            return "register";
        }

        // Try to create user
        User user = sellerService.registerUser(name, email, password, phone, role,
                address != null ? address : "",
                city != null ? city : "",
                state != null ? state : "",
                pincode != null ? pincode : "");

        if (user == null) {
            model.addAttribute("error", "Email already registered. Please use a different email.");
            return "register";
        }

        // Registration successful
        model.addAttribute("success", "Account created successfully! You can now log in.");
        return "redirect:/login?registered=true";
    }
}
