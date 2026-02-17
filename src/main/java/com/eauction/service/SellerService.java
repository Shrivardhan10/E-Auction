package com.eauction.service;

import com.eauction.model.entity.User;
import com.eauction.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.Optional;

@Service
public class SellerService {

    private final UserRepository userRepository;

    public SellerService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Authenticate any user by email, password, and role.
     * Returns the User on success, null on failure.
     */
    public User authenticate(String email, String password, String role) {
        return userRepository.findByEmail(email)
                .filter(u -> role.equals(u.getRole()))
                .filter(u -> u.getPasswordHash().equals(password))
                .orElse(null);
    }

    /**
     * Authenticate any user by email and password (role-agnostic).
     */
    public User authenticate(String email, String password) {
        return userRepository.findByEmail(email)
                .filter(u -> u.getPasswordHash().equals(password))
                .orElse(null);
    }

    /**
     * Register a new user.
     * Returns the created user on success, or null if email already exists.
     */
    public User registerUser(String name, String email, String password, String phone, String role,
                             String address, String city, String state, String pincode) {
        // Check if email already exists
        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            return null;
        }

        // Create new user
        User user = new User();
        user.setUserId(UUID.randomUUID());
        user.setName(name);
        user.setEmail(email);
        user.setPasswordHash(password);  // In production, use bcrypt
        user.setPhone(phone);
        user.setRole(role);
        user.setAddress(address);
        user.setCity(city);
        user.setState(state);
        user.setPincode(pincode);
        user.setIsActive(true);

        return userRepository.save(user);
    }

    public User findById(UUID userId) {
        return userRepository.findById(userId).orElse(null);
    }

    public User findByEmail(String email) {
        return userRepository.findByEmail(email).orElse(null);
    }
}


