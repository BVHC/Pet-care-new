package com.petcare.module.care.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Luồng nền của lần kích gửi ngay (docs/adr/0154): hàng đợi đầy thì bỏ lần kích, <b>không ném</b> — lời gọi nằm trong
 * {@code afterCommit} của request, ném ở đó thành 500 dù dữ liệu đã commit.
 */
class MailKickExecutorConfigTest {

    private final ThreadPoolTaskExecutor executor = new MailKickExecutorConfig().mailKickExecutor();
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        executor.shutdown();
    }

    @Test
    void fullQueueDropsKickWithoutThrowing() throws Exception {
        executor.initialize();
        CountDownLatch busy = new CountDownLatch(MailKickExecutorConfig.THREADS);
        AtomicInteger ran = new AtomicInteger();
        Runnable blocker = () -> {
            busy.countDown();
            await(release);
            ran.incrementAndGet();
        };
        for (int i = 0; i < MailKickExecutorConfig.THREADS; i++) {
            executor.submit(blocker);
        }
        assertThat(busy.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < MailKickExecutorConfig.QUEUE_CAPACITY; i++) {
            executor.submit(ran::incrementAndGet);
        }

        assertThatCode(() -> executor.submit(ran::incrementAndGet)).doesNotThrowAnyException();
        assertThatCode(() -> executor.execute(ran::incrementAndGet)).doesNotThrowAnyException();

        release.countDown();
        executor.shutdown();
        assertThat(executor.getThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get()).as("2 đang chạy + 200 trong hàng đợi; 2 lần kích thừa bị bỏ")
                .isEqualTo(MailKickExecutorConfig.THREADS + MailKickExecutorConfig.QUEUE_CAPACITY);
    }

    @Test
    void poolIsBoundedAndNamed() {
        executor.initialize();

        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(2);
        assertThat(executor.getQueueCapacity()).isEqualTo(200);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("mail-kick-");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
