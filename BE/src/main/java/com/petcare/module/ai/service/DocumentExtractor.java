package com.petcare.module.ai.service;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.TooManyRequestsException;

import jakarta.annotation.PreDestroy;

/**
 * Chạy việc trích văn bản của file nạp trên luồng riêng với hết giờ (BR-AI-29, docs/adr/0134 D1): hết giờ thì {@code cancel(true)}
 * và 400 {@code BR-AI-29}, nên request không treo vì một file PDF/Word hỏng. Số luồng có hạn và <b>không</b> có hàng đợi: hết
 * luồng thì 429 (một luồng treo không kéo theo cả hàng request chờ).
 *
 * <p>Giới hạn đã biết: PDFBox và POI không luôn tôn trọng interrupt, nên một luồng có thể chạy tiếp sau khi request đã trả
 * lời và giữ chỗ trong pool tới khi xong; trong lúc đó request mới nhận 429 khi cả pool bận.
 */
@Component
public class DocumentExtractor {

    /** Hết giờ trích cố định, không phải [CFG] (ADR-0134 D1). */
    public static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int THREADS = 2;
    private static final long RETRY_AFTER_SECONDS = 5;

    private final Duration timeout;
    private final ThreadPoolExecutor pool;

    public DocumentExtractor() {
        this(TIMEOUT, THREADS);
    }

    DocumentExtractor(Duration timeout, int threads) {
        this.timeout = timeout;
        AtomicInteger counter = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "ai-extract-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public <T> T run(Callable<T> task) {
        Future<T> future;
        try {
            future = pool.submit(task);
        } catch (RejectedExecutionException e) {
            throw new TooManyRequestsException(RETRY_AFTER_SECONDS);
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BusinessRuleViolationException(DocumentFileReader.RULE,
                    "Hết thời gian trích văn bản (quá " + Math.max(1, timeout.toSeconds()) + " giây)");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Trích văn bản thất bại", e.getCause());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bị ngắt khi trích văn bản", e);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
