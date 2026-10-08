package com.petcare.module.identity;

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

/**
 * Khi có cron, job ST02 thật sự được đăng ký với scheduler (docs/adr/0013): tên property đúng. Zone giờ Việt Nam kiểm
 * ở {@code PendingAccountCleanupJobTest}; profile test không lên lịch kiểm ở {@code PendingAccountCleanupIT}.
 */
@SpringBootTest(properties = "app.jobs.pending-account-cleanup.cron=0 */15 * * * *")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PendingAccountCleanupScheduleIT {

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Test
    void registeredAsCronTaskWithConfiguredExpression() {
        List<CronTask> tasks = scheduledTasks.getScheduledTasks().stream()
                .filter(task -> task.toString().contains("PendingAccountCleanupJob.run"))
                .map(task -> task.getTask())
                .filter(CronTask.class::isInstance).map(CronTask.class::cast)
                .toList();

        assertThat(tasks).singleElement().extracting(CronTask::getExpression).isEqualTo("0 */15 * * * *");
    }
}
