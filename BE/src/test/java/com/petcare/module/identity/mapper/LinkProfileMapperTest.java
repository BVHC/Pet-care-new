package com.petcare.module.identity.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.petcare.module.customer.api.CustomerQueryApi.LinkCandidate;
import com.petcare.module.identity.dto.LinkCandidateResponse;
import com.petcare.module.identity.dto.LinkResult;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.service.OtpService.IssuedOtp;

/** Mapper thật (docs/adr/0027): so cả record, mỗi trường một giá trị khác nhau để bắt trường lấy nhầm nguồn. */
class LinkProfileMapperTest {

    private final LinkProfileMapper mapper = Mappers.getMapper(LinkProfileMapper.class);

    @Test
    void candidateKeepsMaskedNameAndEmailFlag() {
        assertThat(mapper.toCandidate(new LinkCandidate(11L, "Ng*** V** A", false)))
                .isEqualTo(new LinkCandidateResponse(11L, "Ng*** V** A", false));
        assertThat(mapper.toCandidates(List.of(new LinkCandidate(11L, "Ng*** A", true),
                new LinkCandidate(12L, "Tr*** B", false))))
                .containsExactly(new LinkCandidateResponse(11L, "Ng*** A", true),
                        new LinkCandidateResponse(12L, "Tr*** B", false));
    }

    @Test
    void emptyCandidateListStaysEmpty() {
        assertThat(mapper.toCandidates(List.of())).isEmpty();
    }

    @Test
    void otpSentTakesResendTimeFromIssuedOtpAndMaskedEmailAsGiven() {
        Instant expiresAt = Instant.parse("2026-10-10T03:05:00Z");
        Instant resendAt = Instant.parse("2026-10-10T03:01:00Z");

        assertThat(mapper.toOtpSentResponse(new IssuedOtp("123456", expiresAt, resendAt, 5), "ng***@gmail.com"))
                .isEqualTo(new OtpSentResponse(resendAt, "ng***@gmail.com"));
    }

    @Test
    void linkResultCarriesCounterProfile() {
        assertThat(mapper.toLinkResult(77L)).isEqualTo(new LinkResult(77L));
    }
}
