package com.petcare.module.customer.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.customer.dto.CustomerProfileResponse;
import com.petcare.module.customer.dto.PetBriefResponse;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.entity.Pet;

/**
 * Hồ sơ khách và thú rút gọn cho nhân viên (customer-v1 #8–11). Khác {@code CustomerProfileMapper} (khách tự xem, email
 * lấy từ tài khoản): nhân viên thấy {@code customers.email} đúng như lưu (hồ sơ có tài khoản thì đã bằng email tài khoản
 * — BR-TK-19). {@code unmappedTargetPolicy = ERROR}: thiếu một trường của hợp đồng là lỗi biên dịch.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CustomerStaffMapper {

    @Mapping(target = "customerId", source = "id")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "hasAccount", expression = "java(customer.getAccountId() != null)")
    CustomerProfileResponse toProfile(Customer customer);

    @Mapping(target = "petId", source = "id")
    @Mapping(target = "deceased", expression = "java(pet.isDeceased())")
    PetBriefResponse toBrief(Pet pet);

    List<PetBriefResponse> toBriefs(List<Pet> pets);
}
