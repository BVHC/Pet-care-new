package com.petcare.module.procurement.repository;

import com.petcare.module.procurement.entity.PurchaseRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PurchaseRequestRepository extends JpaRepository<PurchaseRequest, UUID> {
}
