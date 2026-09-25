package com.petcare.module.procurement.repository;

import com.petcare.module.procurement.entity.PurchaseOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, UUID> {

    List<PurchaseOrderLine> findAllByPurchaseOrderId(UUID purchaseOrderId);
}
