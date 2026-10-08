package com.petcare.module.identity.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.petcare.module.identity.dto.AccountSummary;
import com.petcare.module.identity.dto.LoginResponse;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.service.SessionService.OpenedSession;

@Mapper(componentModel = "spring")
public interface LoginMapper {

    @Mapping(target = "isLocked", source = "locked")
    AccountSummary toAccountSummary(Account account);

    @Mapping(target = "accessToken", source = "session.accessToken")
    @Mapping(target = "expiresAt", source = "session.expiresAt")
    @Mapping(target = "account", source = "account")
    LoginResponse toLoginResponse(OpenedSession session, Account account, boolean linkDecisionPending);
}
