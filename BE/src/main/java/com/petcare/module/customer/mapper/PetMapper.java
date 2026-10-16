package com.petcare.module.customer.mapper;

import java.math.BigDecimal;
import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.customer.dto.PetResponse;
import com.petcare.module.customer.dto.WeightRecordResponse;
import com.petcare.module.customer.entity.Pet;
import com.petcare.module.customer.entity.WeightRecord;

/**
 * Thú cưng và lịch sử cân nặng (customer-v1 {@code PetResponse}, {@code WeightRecord}). Ba trường của {@code PetResponse}
 * không nằm trong bảng {@code pets} — {@code speciesLocked}, {@code currentWeightKg}, {@code deletable} — do service tính
 * và truyền vào. Mọi trường đích khai báo tường minh, {@code unmappedTargetPolicy = ERROR}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PetMapper {

    @Mapping(target = "petId", source = "pet.id")
    @Mapping(target = "customerId", source = "pet.customerId")
    @Mapping(target = "name", source = "pet.name")
    @Mapping(target = "species", source = "pet.species")
    @Mapping(target = "sex", source = "pet.sex")
    @Mapping(target = "breed", source = "pet.breed")
    @Mapping(target = "birthDate", source = "pet.birthDate")
    @Mapping(target = "birthDateEstimated", source = "pet.birthDateEstimated")
    @Mapping(target = "color", source = "pet.color")
    @Mapping(target = "isNeutered", source = "pet.neutered")
    @Mapping(target = "photoUrl", source = "pet.photoUrl")
    @Mapping(target = "deceasedOn", source = "pet.deceasedOn")
    @Mapping(target = "speciesLocked", source = "speciesLocked")
    @Mapping(target = "currentWeightKg", source = "currentWeightKg")
    @Mapping(target = "deletable", source = "deletable")
    PetResponse toPetResponse(Pet pet, boolean speciesLocked, BigDecimal currentWeightKg, boolean deletable);

    @Mapping(target = "weightRecordId", source = "id")
    WeightRecordResponse toWeightResponse(WeightRecord record);

    List<WeightRecordResponse> toWeightResponses(List<WeightRecord> records);
}
