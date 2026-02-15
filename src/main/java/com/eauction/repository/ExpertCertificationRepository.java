package com.eauction.repository;

import com.eauction.model.entity.ExpertCertification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExpertCertificationRepository extends JpaRepository<ExpertCertification, UUID> {

    Optional<ExpertCertification> findByItemId(UUID itemId);
}
