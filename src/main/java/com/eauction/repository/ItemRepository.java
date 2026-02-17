package com.eauction.repository;

import com.eauction.model.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    List<Item> findBySellerIdOrderByCreatedAtDesc(UUID sellerId);
    List<Item> findByAdminStatus(String adminStatus);
}

