package com.petcare.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

class TraceIdFilterTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void keepsValidClientTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceContext.HEADER, "demo-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInChain.set(TraceContext.current()));

        assertThat(seenInChain.get()).isEqualTo("demo-1");
        assertThat(response.getHeader(TraceContext.HEADER)).isEqualTo("demo-1");
    }

    @Test
    void generatesTraceIdWhenHeaderMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> { });

        assertThat(response.getHeader(TraceContext.HEADER)).matches(UUID_PATTERN);
    }

    @Test
    void replacesUnsafeClientTraceId() throws Exception {
        for (String unsafe : new String[] {"abc\nFAKE LOG LINE", "a".repeat(65), "x y"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(TraceContext.HEADER, unsafe);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (req, res) -> { });

            assertThat(response.getHeader(TraceContext.HEADER)).matches(UUID_PATTERN);
        }
    }

    @Test
    void clearsMdcEvenWhenChainFails() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }
}
