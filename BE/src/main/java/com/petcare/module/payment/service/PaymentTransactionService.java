package com.petcare.module.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.payment.api.PaymentTransactionApi;
import com.petcare.module.payment.repository.PaymentTransactionDao;

/** {@link PaymentTransactionApi} thật (task 125, docs/adr/0145): chạy trong transaction của shop, sau khóa đơn và Order. */
@Service
public class PaymentTransactionService implements PaymentTransactionApi {

    private final PaymentTransactionDao transactions;
    private final Clock clock;

    public PaymentTransactionService(PaymentTransactionDao transactions, Clock clock) {
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelPendingForOnlineOrder(Long onlineOrderId) {
        require(onlineOrderId);
        return transactions.cancelPending(onlineOrderId, Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasSucceededForOnlineOrder(Long onlineOrderId) {
        require(onlineOrderId);
        return transactions.existsSucceeded(onlineOrderId);
    }

    private static void require(Long onlineOrderId) {
        if (onlineOrderId == null) {
            throw new IllegalArgumentException("onlineOrderId is required");
        }
    }
}
