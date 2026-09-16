package com.petcare.module.pet.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RULE-04-05 / RULE-04-07 — mirror OtpExpiryJob: @Scheduled đơn giản, không
 * distributed lock (an toàn với đúng 1 backend instance hiện tại).
 */
@Component
@ConditionalOnProperty(prefix = "app.caregiver-expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CaregiverExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(CaregiverExpiryJob.class);

    private final CaregiverExpiryService caregiverExpiryService;

    public CaregiverExpiryJob(CaregiverExpiryService caregiverExpiryService) {
        this.caregiverExpiryService = caregiverExpiryService;
    }

    @Scheduled(cron = "${app.caregiver-expiry.cron:0 */15 * * * *}", zone = "Asia/Ho_Chi_Minh")
    public void expireOutstandingDelegations() {
        int invitations = caregiverExpiryService.processInvitationExpiry();
        int delegations = caregiverExpiryService.processDelegationExpiry();
        if (invitations > 0 || delegations > 0) {
            log.info("CaregiverExpiryJob: expired {} invitation(s), {} delegation(s)",
                    invitations, delegations);
        }
    }
}
