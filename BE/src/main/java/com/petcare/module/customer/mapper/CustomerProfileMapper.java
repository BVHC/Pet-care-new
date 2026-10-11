package com.petcare.module.customer.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.customer.dto.CustomerProfileResponse;
import com.petcare.module.customer.entity.Customer;

/**
 * {@code GET/PATCH /api/me/customer-profile}. Mọi trường đích khai báo tường minh và
 * {@code unmappedTargetPolicy = ERROR}: thiếu một trường của hợp đồng là lỗi biên dịch. {@code email} là email tài khoản
 * do service truyền vào (docs/adr/0028), không phải {@code customers.email}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CustomerProfileMapper {

    @Mapping(target = "customerId", source = "customer.id")
    @Mapping(target = "fullName", source = "customer.fullName")
    @Mapping(target = "phone", source = "customer.phone")
    @Mapping(target = "email", source = "accountEmail")
    @Mapping(target = "avatarUrl", source = "customer.avatarUrl")
    @Mapping(target = "createdChannel", source = "customer.createdChannel")
    @Mapping(target = "hasAccount", expression = "java(customer.getAccountId() != null)")
    @Mapping(target = "linkDecisionPending", source = "customer.linkDecisionPending")
    CustomerProfileResponse toResponse(Customer customer, String accountEmail);
}
