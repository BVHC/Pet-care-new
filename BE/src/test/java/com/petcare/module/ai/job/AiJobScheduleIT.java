package com.petcare.module.ai.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;

/**
 * Khi có cron, ST22 và ST23 thật sự được đăng ký với scheduler (docs/adr/0007, 0130): tên property đúng. Lịch mặc định của
 * ST22 chạy ở giây 45 của mọi phút chia 5 (mọi phút lẻ 5 đã có job dài, nên chọn giây lệch để không cùng lúc với chúng).
 */
@SpringBootTest(properties = {"app.jobs.knowledge-index.cron=45 */5 * * * *", "app.jobs.chat-log-cleanup.cron=29 0 4 * * *"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AiJobScheduleIT {

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    private List<CronTask> tasksOf(String method) {
        return scheduledTasks.getScheduledTasks().stream()
                .filter(task -> task.toString().contains(method))
                .map(task -> task.getTask())
                .filter(CronTask.class::isInstance).map(CronTask.class::cast)
                .toList();
    }

    @Test
    void knowledgeIndexIsRegisteredAsACronTaskWithTheConfiguredExpression() {
        assertThat(tasksOf("KnowledgeIndexJob.run")).singleElement().extracting(CronTask::getExpression)
                .isEqualTo("45 */5 * * * *");
    }

    @Test
    void chatLogCleanupIsRegisteredAsACronTaskWithTheConfiguredExpression() {
        assertThat(tasksOf("ChatLogCleanupJob.run")).singleElement().extracting(CronTask::getExpression)
                .isEqualTo("29 0 4 * * *");
    }

    @Test
    void knowledgeIndexDefaultCronFiresAtSecond45OfEveryFifthMinute() {
        CronExpression cron = CronExpression.parse("45 */5 * * * *");
        LocalDateTime t = LocalDateTime.of(2026, 11, 10, 0, 0).minusSeconds(1);
        for (int i = 0; i < 288; i++) {
            t = cron.next(t);
            assertThat(t.getMinute() % 5).isZero();
            assertThat(t.getSecond()).isEqualTo(45);
        }
        assertThat(t).isEqualTo(LocalDateTime.of(2026, 11, 10, 23, 55, 45));
    }
}
