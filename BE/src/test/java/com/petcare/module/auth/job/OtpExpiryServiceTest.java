package com.petcare.module.auth.job;

import com.petcare.module.auth.repository.OtpRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtpExpiryServiceTest {

    @Mock
    private OtpRepository otpRepository;

    @Test
    void expireOutstandingOtps_delegatesToRepository_andReturnsUpdatedCount() {
        when(otpRepository.markExpiredOtpsAsUsed(any(LocalDateTime.class))).thenReturn(3);

        OtpExpiryService service = new OtpExpiryService(otpRepository);
        int updated = service.expireOutstandingOtps();

        assertThat(updated).isEqualTo(3);
    }
}
