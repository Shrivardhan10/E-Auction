package com.eauction.repository;

import com.eauction.model.entity.Auction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuctionRepository extends JpaRepository<Auction, UUID> {
    Optional<Auction> findByItemId(UUID itemId);

    List<Auction> findByStatus(String status);

    long countByStatus(String status);

    long countByWinnerId(UUID winnerId);

    List<Auction> findByWinnerId(UUID winnerId);
}
