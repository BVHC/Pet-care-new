package com.petcare.module.identity.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.customer.api.CustomerQueryApi.LinkCandidate;
import com.petcare.module.identity.dto.LinkCandidateResponse;
import com.petcare.module.identity.dto.LinkResult;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.service.OtpService.IssuedOtp;

/**
 * UC07 — liên kết hồ sơ khách có sẵn (docs/adr/0027). Mọi trường đích khai báo tường minh và
 * {@code unmappedTargetPolicy = ERROR}: thiếu một trường của contract là lỗi biên dịch.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface LinkProfileMapper {

    @Mapping(target = "customerId", source = "customerId")
    @Mapping(target = "maskedFullName", source = "maskedFullName")
    @Mapping(target = "hasEmail", source = "hasEmail")
    LinkCandidateResponse toCandidate(LinkCandidate candidate);

    /** Caller bảo đảm {@code candidates} khác null (MapStruct trả null cho list null). */
    List<LinkCandidateResponse> toCandidates(List<LinkCandidate> candidates);

    /** {@code maskedEmail}: email hồ sơ tại quầy đã che — mã gửi tới đó, không tới email tài khoản (BR-TK-04). */
    @Mapping(target = "resendAvailableAt", source = "otp.resendAvailableAt")
    @Mapping(target = "maskedEmail", source = "maskedEmail")
    OtpSentResponse toOtpSentResponse(IssuedOtp otp, String maskedEmail);

    @Mapping(target = "customerId", source = "customerId")
    LinkResult toLinkResult(Long customerId);
}
