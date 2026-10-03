package com.petcare.platform.config;

import org.slf4j.MDC;

/** Truy cập traceId của request hiện tại (do {@link TraceIdFilter} đặt vào MDC). */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private TraceContext() {
    }

    /** traceId hiện tại, hoặc {@code null} nếu đang chạy ngoài request (job định kỳ...). */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
