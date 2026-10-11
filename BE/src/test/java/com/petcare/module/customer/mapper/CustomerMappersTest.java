package com.petcare.module.customer.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.customer.dto.AddressResponse;
import com.petcare.module.customer.dto.CustomerProfileResponse;
import com.petcare.module.customer.entity.Address;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.entity.CustomerChannel;

/**
 * Mapping hồ sơ khách / địa chỉ (customer-v1 {@code CustomerProfile}, {@code Address}; docs/adr/0028): mỗi trường một giá
 * trị riêng để bắt map nhầm nguồn; email lấy từ tham số tài khoản, không từ {@code customers.email}; tên JSON
 * {@code isDefault} giữ đúng hợp đồng.
 */
class CustomerMappersTest {

    private final CustomerProfileMapper profiles = new CustomerProfileMapperImpl();
    private final AddressMapper addresses = new AddressMapperImpl();

    @Test
    void profileTakesEveryFieldFromItsOwnSource() {
        Customer customer = customer(11L, 7L, "0901234567", "ho-so@petcare.test", "https://img/a.png", true);

        assertThat(profiles.toResponse(customer, "tai-khoan@petcare.test")).isEqualTo(new CustomerProfileResponse(
                11L, "Nguyễn An", "0901234567", "tai-khoan@petcare.test", "https://img/a.png",
                CustomerChannel.COUNTER, true, true));
    }

    @Test
    void profileNullableFieldsStayNullAndHasAccountFollowsColumn() {
        Customer customer = customer(11L, null, null, null, null, false);

        assertThat(profiles.toResponse(customer, null)).isEqualTo(new CustomerProfileResponse(11L, "Nguyễn An", null,
                null, null, CustomerChannel.COUNTER, false, false));
    }

    @Test
    void addressMapsDefaultFlagFromIsDefaultColumn() {
        Address address = Address.create(11L, "An", "0901234567", "12 Lê Lợi", null, "Hà Nội", true);
        ReflectionTestUtils.setField(address, "id", 4L);

        assertThat(addresses.toResponse(address)).isEqualTo(new AddressResponse(4L, "An", "0901234567", "12 Lê Lợi",
                null, "Hà Nội", true));
        assertThat(addresses.toResponses(List.of())).isEmpty();
    }

    @Test
    void jsonKeepsContractNames() throws Exception {
        ObjectMapper json = new ObjectMapper();

        assertThat(json.readTree(json.writeValueAsString(
                new AddressResponse(4L, "An", "0901234567", "12 Lê Lợi", null, "Hà Nội", true))).fieldNames())
                .toIterable().containsExactlyInAnyOrder("addressId", "receiverName", "receiverPhone", "addressLine",
                        "ward", "province", "isDefault");
        assertThat(json.readTree(json.writeValueAsString(new CustomerProfileResponse(11L, "An", null, null, null,
                CustomerChannel.ONLINE, true, false))).fieldNames()).toIterable().containsExactlyInAnyOrder(
                "customerId", "fullName", "phone", "email", "avatarUrl", "createdChannel", "hasAccount",
                "linkDecisionPending");
    }

    private static Customer customer(long id, Long accountId, String phone, String email, String avatarUrl,
            boolean pending) {
        Customer customer = BeanUtils.instantiateClass(Customer.class);
        ReflectionTestUtils.setField(customer, "id", id);
        ReflectionTestUtils.setField(customer, "accountId", accountId);
        ReflectionTestUtils.setField(customer, "fullName", "Nguyễn An");
        ReflectionTestUtils.setField(customer, "phone", phone);
        ReflectionTestUtils.setField(customer, "email", email);
        ReflectionTestUtils.setField(customer, "avatarUrl", avatarUrl);
        ReflectionTestUtils.setField(customer, "createdChannel", CustomerChannel.COUNTER);
        ReflectionTestUtils.setField(customer, "linkDecisionPending", pending);
        return customer;
    }
}
