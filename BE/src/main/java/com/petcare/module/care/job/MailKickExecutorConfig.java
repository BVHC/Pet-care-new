package com.petcare.module.care.job;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import lombok.extern.slf4j.Slf4j;

/**
 * Luồng nền gửi ngay email luồng HIGH sau commit (docs/adr/0154): 2 thread, hàng đợi 200. Hàng đợi đầy thì <b>bỏ lần
 * kích và log</b>, không ném: lời gọi nằm trong {@code afterCommit} của request, ném ở đó thành 500 dù dữ liệu đã
 * commit. Dòng bị bỏ vẫn {@code PENDING} và được job HIGH gửi trong một lượt (5 s). Khi tắt app, chờ tối đa 30 s cho
 * thư đang gửi; thư chưa gửi được job quét lại sau hạn giữ.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class MailKickExecutorConfig {

    public static final String EXECUTOR = "mailKickExecutor";
    static final int THREADS = 2;
    static final int QUEUE_CAPACITY = 200;

    @Bean(EXECUTOR)
    public ThreadPoolTaskExecutor mailKickExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(THREADS);
        executor.setMaxPoolSize(THREADS);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("mail-kick-");
        executor.setRejectedExecutionHandler(MailKickExecutorConfig::dropKick);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    static void dropKick(Runnable task, ThreadPoolExecutor executor) {
        log.warn("NOTIFICATION_KICK_DROPPED queued={} active={}", executor.getQueue().size(), executor.getActiveCount());
    }
}
