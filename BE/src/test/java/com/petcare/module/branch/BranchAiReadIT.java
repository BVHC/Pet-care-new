package com.petcare.module.branch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.petcare.module.AiReadItSupport;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;

/** I-4 (07 §11, AI-0): chi nhánh cấp cứu và dịch vụ đang bật ở chi nhánh ACTIVE, trên Postgres thật. */
class BranchAiReadIT extends AiReadItSupport {

    @Autowired private BranchQueryApi branches;

    @Test
    void emergencyBranchesAreActiveOnesThatAcceptAfterHoursSortedByName() {
        long first = fx.branch("AI-A-", "ACTIVE", true);
        long second = fx.branch("AI-B-", "ACTIVE", true);
        long noEmergency = fx.branch("AI-C-", "ACTIVE", false);
        long draft = fx.branch("AI-D-", "DRAFT", true);

        List<BranchSummary> emergency = branches.findEmergencyBranches();
        List<Long> ids = emergency.stream().map(BranchSummary::branchId).toList();

        assertThat(ids).contains(first, second).doesNotContain(noEmergency, draft);
        assertThat(ids.indexOf(first)).as("theo tên").isLessThan(ids.indexOf(second));
        assertThat(emergency).allSatisfy(b -> {
            assertThat(b.status()).isEqualTo(BranchStatus.ACTIVE);
            assertThat(b.acceptsAfterHoursEmergency()).isTrue();
        });
    }

    @Test
    void servicesEnabledAnywhereCountOnlyEnabledSettingsOfActiveBranches() {
        long active1 = fx.branch("AI-E-", "ACTIVE", false);
        long active2 = fx.branch("AI-F-", "ACTIVE", false);
        long draft = fx.branch("AI-G-", "DRAFT", false);
        long enabledTwice = fx.service("GROOMING", true);
        long enabledOnlyInDraft = fx.service("GROOMING", true);
        long disabledEverywhere = fx.service("GROOMING", true);
        long noSetting = fx.service("GROOMING", true);
        fx.branchService(active1, enabledTwice, true);
        fx.branchService(active2, enabledTwice, true);
        fx.branchService(draft, enabledOnlyInDraft, true);
        fx.branchService(active1, disabledEverywhere, false);
        fx.branchService(draft, disabledEverywhere, true);

        assertThat(branches.findServiceIdsEnabledAnywhere()).contains(enabledTwice)
                .doesNotContain(enabledOnlyInDraft, disabledEverywhere, noSetting);
    }
}
