package com.petcare.module.auth.job;

import com.petcare.module.auth.repository.OtpRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OtpExpiryService {

    private final OtpRepository otpRepository;

    @Transactional
    public int expireOutstandingOtps() {
        return otpRepository.markExpiredOtpsAsUsed(LocalDateTime.now());
    }
}
