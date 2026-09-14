package com.petcare.module.auth.mapper;

import com.petcare.module.auth.dto.RegisterResponse;
import com.petcare.module.auth.dto.ResendOtpResponse;
import com.petcare.module.auth.dto.VerifyOtpResponse;
import com.petcare.module.auth.entity.Account;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AuthMapper {

    @Mapping(target = "accountId", source = "id")
    RegisterResponse toRegisterResponse(Account account);

    @Mapping(target = "accountId", source = "id")
    VerifyOtpResponse toVerifyOtpResponse(Account account);

    @Mapping(target = "accountId", source = "id")
    ResendOtpResponse toResendOtpResponse(Account account);
}
