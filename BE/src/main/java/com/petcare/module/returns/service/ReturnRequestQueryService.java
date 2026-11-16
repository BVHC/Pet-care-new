package com.petcare.module.returns.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.returns.api.ReturnRequestQueryApi;
import com.petcare.module.returns.repository.ReturnRequestDao;

/** {@link ReturnRequestQueryApi} thật (task 126, docs/adr/0146): đọc trong transaction của ST12, sau khóa {@code online_orders}. */
@Service
public class ReturnRequestQueryService implements ReturnRequestQueryApi {

    private final ReturnRequestDao requests;

    public ReturnRequestQueryService(ReturnRequestDao requests) {
        this.requests = requests;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasOpenRequestForOrder(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId is required");
        }
        return requests.existsOpen(orderId);
    }
}
