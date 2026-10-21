package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;

/** Khi có cron, hai job ST13 thật sự được đăng ký với scheduler (docs/adr/0064): tên property đúng. */
@SpringBootTest(properties = {"app.jobs.cashier-shift-auto-close.cron=0 4/5 * * * *",
        "app.jobs.pending-order-digest.cron=0 50 23 * * *"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CashierShiftAutoCloseScheduleIT {

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    private List<String> expressionsOf(String jobMethod) {
        return scheduledTasks.getScheduledTasks().stream()
                .filter(task -> task.toString().contains(jobMethod))
                .map(task -> task.getTask())
                .filter(CronTask.class::isInstance).map(CronTask.class::cast)
                .map(CronTask::getExpression).toList();
    }

    @Test
    void autoCloseIsRegisteredAsCronTaskWithTheConfiguredExpression() {
        assertThat(expressionsOf("CashierShiftAutoCloseJob.run")).containsExactly("0 4/5 * * * *");
    }

    @Test
    void pendingOrderDigestIsRegisteredAsCronTaskWithTheConfiguredExpression() {
        assertThat(expressionsOf("PendingOrderDigestJob.run")).containsExactly("0 50 23 * * *");
    }
}
