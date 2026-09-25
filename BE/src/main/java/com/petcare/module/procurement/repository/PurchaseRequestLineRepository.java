package com.petcare.module.procurement.repository;

import com.petcare.module.procurement.entity.PurchaseRequestLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PurchaseRequestLineRepository extends JpaRepository<PurchaseRequestLine, UUID> {

    List<PurchaseRequestLine> findAllByPurchaseRequestId(UUID purchaseRequestId);
}
