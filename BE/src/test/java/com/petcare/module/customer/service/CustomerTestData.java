package com.petcare.module.customer.service;

import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.customer.entity.Address;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.entity.CustomerChannel;

/** Entity dựng sẵn cho unit test của module customer (constructor mặc định là {@code protected}). */
final class CustomerTestData {

    private CustomerTestData() {
    }

    static Customer customer(long id, Long accountId, CustomerChannel channel, String fullName, String phone,
            String email, String avatarUrl, boolean linkDecisionPending) {
        Customer customer = BeanUtils.instantiateClass(Customer.class);
        ReflectionTestUtils.setField(customer, "id", id);
        ReflectionTestUtils.setField(customer, "accountId", accountId);
        ReflectionTestUtils.setField(customer, "createdChannel", channel);
        ReflectionTestUtils.setField(customer, "fullName", fullName);
        ReflectionTestUtils.setField(customer, "phone", phone);
        ReflectionTestUtils.setField(customer, "email", email);
        ReflectionTestUtils.setField(customer, "avatarUrl", avatarUrl);
        ReflectionTestUtils.setField(customer, "linkDecisionPending", linkDecisionPending);
        return customer;
    }

    static Address address(long id, long customerId, boolean isDefault) {
        Address address = Address.create(customerId, "Người nhận " + id, "090000000" + (id % 10), "Số " + id,
                "Phường " + id, "Tỉnh " + id, isDefault);
        ReflectionTestUtils.setField(address, "id", id);
        return address;
    }
}
