package com.eauction.repository;

import com.eauction.model.entity.Seller;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SellerRepository extends JpaRepository<Seller, UUID> {

    Optional<Seller> findByEmail(String email);

    Optional<Seller> findByEmailAndRole(String email, String role);
}

