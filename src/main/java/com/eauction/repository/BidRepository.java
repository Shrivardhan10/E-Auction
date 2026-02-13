package com.eauction.repository;

import com.eauction.model.entity.Bid;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface BidRepository extends JpaRepository<Bid, UUID> {

    Optional<Bid> findTopByAuctionIdOrderByAmountDesc(UUID auctionId);
    List<Bid> findByAuctionIdOrderByCreatedAtDesc(UUID auctionId);

}
