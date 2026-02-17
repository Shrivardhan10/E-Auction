package com.eauction.repository;

import com.eauction.model.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByAuctionId(UUID auctionId);

    Optional<Payment> findByAuctionIdAndBidderIdAndPaymentType(UUID auctionId, UUID bidderId, String paymentType);

    Optional<Payment> findByAuctionIdAndPaymentTypeAndStatus(UUID auctionId, String paymentType, String status);
}
