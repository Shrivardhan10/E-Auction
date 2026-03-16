package com.eauction.repository;

import com.eauction.model.entity.DeliveryVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryVerificationRepository extends JpaRepository<DeliveryVerification, UUID> {

    Optional<DeliveryVerification> findByAuctionId(UUID auctionId);

    List<DeliveryVerification> findByAgentIdOrderByCreatedAtDesc(UUID agentId);

    List<DeliveryVerification> findByStatus(String status);

    List<DeliveryVerification> findByAgentIdAndStatus(UUID agentId, String status);
}
