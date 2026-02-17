package com.eauction.repository;

import com.eauction.model.entity.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.UUID;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    @Query("SELECT COALESCE(SUM(s.brokerageAmount),0) FROM Settlement s WHERE s.status='COMPLETED'")
    BigDecimal sumBrokerage();
}
