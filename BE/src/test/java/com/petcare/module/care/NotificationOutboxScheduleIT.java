package com.petcare.module.care;

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
 * Khi có cron, ba job ST20 thật sự được đăng ký với scheduler (docs/adr/0012, 0014; mẫu
 * {@code SessionCleanupScheduleIT}). Dùng lịch 03:00 / 04:00 / 05:00 thay cho lịch thật (5 s / 10 s / 5 s) để job không
 * tự chạy trong lúc test.
 */
@SpringBootTest(properties = {
        "app.jobs.notification-outbox.priority-cron=0 0 3 * * *",
        "app.jobs.notification-outbox.cron=0 0 4 * * *",
        "app.jobs.notification-outbox.in-app-cron=0 0 5 * * *"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class NotificationOutboxScheduleIT {

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Test
    void allLanesRegisteredAsCronTasks() {
        assertThat(cronOf("PriorityNotificationJob.run")).containsExactly("0 0 3 * * *");
        assertThat(cronOf("NotificationOutboxJob.run")).containsExactly("0 0 4 * * *");
        assertThat(cronOf("InAppNotificationJob.run")).containsExactly("0 0 5 * * *");
    }

    private List<String> cronOf(String method) {
        return scheduledTasks.getScheduledTasks().stream()
                .filter(task -> task.toString().contains(method))
                .map(task -> task.getTask())
                .filter(CronTask.class::isInstance).map(CronTask.class::cast)
                .map(CronTask::getExpression)
                .toList();
    }
}
