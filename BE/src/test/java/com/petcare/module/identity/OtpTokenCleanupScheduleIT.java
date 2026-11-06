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
 * Khi có cron, job dọn OTP thật sự được đăng ký với scheduler (docs/adr/0007, 0111): chứng minh {@code @EnableScheduling}
 * đang bật và tên property đúng. Zone giờ Việt Nam kiểm ở {@code OtpTokenCleanupJobTest}.
 */
@SpringBootTest(properties = "app.jobs.otp-token-cleanup.cron=17 20 3 * * *")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OtpTokenCleanupScheduleIT {

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Test
    void registeredAsCronTaskWithConfiguredExpression() {
        List<CronTask> tasks = scheduledTasks.getScheduledTasks().stream()
                .filter(task -> task.toString().contains("OtpTokenCleanupJob.run"))
                .map(task -> task.getTask())
                .filter(CronTask.class::isInstance).map(CronTask.class::cast)
                .toList();

        assertThat(tasks).singleElement().extracting(CronTask::getExpression).isEqualTo("17 20 3 * * *");
    }
}
