package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.TooManyRequestsException;

/**
 * Hết giờ trích văn bản 20 giây (BR-AI-29, docs/adr/0134 D1): luồng riêng, hết giờ thì hủy và trả 400 {@code BR-AI-29},
 * hết luồng thì 429 thay vì xếp hàng. Thời hạn nhỏ được tiêm qua constructor để không chờ 20 giây thật.
 */
class DocumentExtractorTest {

    private DocumentExtractor extractor;

    @AfterEach
    void shutdown() {
        if (extractor != null) {
            extractor.shutdown();
        }
    }

    @Test
    void theDefaultDeadlineIsTwentySeconds() {
        assertThat(DocumentExtractor.TIMEOUT).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void aFastTaskReturnsItsResultOnANamedDaemonThreadOutsideTheCaller() throws Exception {
        extractor = new DocumentExtractor();

        String worker = extractor.run(() -> Thread.currentThread().getName() + "/" + Thread.currentThread().isDaemon());

        assertThat(worker).startsWith("ai-extract-").endsWith("/true").isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    void aTaskPastTheDeadlineIsRejectedWith400AndInterruptedInsteadOfWaitedFor() throws Exception {
        extractor = new DocumentExtractor(Duration.ofSeconds(1), 1);
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch seen = new CountDownLatch(1);
        long start = System.nanoTime();

        assertThatThrownBy(() -> extractor.run(() -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                seen.countDown();
                throw e;
            }
            return "xong";
        })).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-AI-29")
                .hasMessageContaining("Hết thời gian trích văn bản").hasMessageContaining("quá 1 giây");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("không chờ hết 30 giây của tác vụ").isLessThan(Duration.ofSeconds(5));
        assertThat(seen.await(3, TimeUnit.SECONDS)).as("tác vụ nhận interrupt").isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    void errorsRaisedByTheTaskReachTheCallerUnchanged() {
        extractor = new DocumentExtractor();
        BusinessRuleViolationException rule = new BusinessRuleViolationException("BR-AI-29", "File hỏng");
        IllegalStateException unexpected = new IllegalStateException("bất ngờ");

        assertThatThrownBy(() -> extractor.run(() -> {
            throw rule;
        })).isSameAs(rule);
        assertThatThrownBy(() -> extractor.run(() -> {
            throw unexpected;
        })).isSameAs(unexpected);
    }

    @Test
    void whenEveryWorkerIsBusyTheCallerGets429InsteadOfAQueue() throws Exception {
        extractor = new DocumentExtractor(Duration.ofSeconds(10), 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                extractor.run(() -> {
                    started.countDown();
                    release.await();
                    return "giữ";
                });
            } catch (Exception ignored) {
                // luồng giữ chỗ: kết quả không quan trọng
            }
        });
        holder.start();
        try {
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> extractor.run(() -> "bị từ chối")).isInstanceOf(TooManyRequestsException.class);
        } finally {
            release.countDown();
            holder.join(3_000);
        }
    }
}
