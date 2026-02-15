package com.eauction.service;

import com.eauction.model.entity.Seller;
import com.eauction.repository.SellerRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class SellerService {

    private final SellerRepository sellerRepository;

    public SellerService(SellerRepository sellerRepository) {
        this.sellerRepository = sellerRepository;
    }

    /**
     * Email + password authentication with plaintext comparison.
     * Returns the Seller on success, null on failure.
     */
    public Seller authenticate(String email, String password) {
        return sellerRepository.findByEmail(email)
                .filter(s -> "SELLER".equals(s.getRole()))
                .filter(s -> s.getPasswordHash().equals(password))
                .orElse(null);
    }

    public Seller findById(UUID userId) {
        return sellerRepository.findById(userId).orElse(null);
    }

    public Seller findByEmail(String email) {
        return sellerRepository.findByEmail(email).orElse(null);
    }
}


