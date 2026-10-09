package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LabRequestGuardFilterTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private MockHttpServletResponse call(LabRequestGuardFilter filter, String method, String uri, String ip)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void readOnlyModeTurnsEveryMutatingLabCallInto503WithAClearMessage() throws Exception {
        LabRequestGuardFilter filter = new LabRequestGuardFilter(LabPropertiesFixtures.withReadOnly(true, 30), mapper);
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            MockHttpServletResponse res = call(filter, method, "/lab/settlements", "9.9.9.9");
            assertThat(res.getStatus()).as(method).isEqualTo(503);
            assertThat(res.getContentAsString()).contains("read-only");
        }
    }

    @Test
    void readOnlyModeStillServesReads() throws Exception {
        LabRequestGuardFilter filter = new LabRequestGuardFilter(LabPropertiesFixtures.withReadOnly(true, 30), mapper);
        assertThat(call(filter, "GET", "/lab/status", "9.9.9.9").getStatus()).isEqualTo(200);
    }

    @Test
    void mutatingCallsBeyondThePerIpBudgetGet429WithRetryAfter() throws Exception {
        LabRequestGuardFilter filter = new LabRequestGuardFilter(LabPropertiesFixtures.withReadOnly(false, 2), mapper);
        assertThat(call(filter, "POST", "/lab/settlements", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/lab/settlements", "1.1.1.1").getStatus()).isEqualTo(200);
        MockHttpServletResponse limited = call(filter, "POST", "/lab/settlements", "1.1.1.1");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotNull();
        assertThat(call(filter, "POST", "/lab/settlements", "2.2.2.2").getStatus()).isEqualTo(200);
    }

    @Test
    void readsAreNotRateLimitedByTheMutationBudget() throws Exception {
        LabRequestGuardFilter filter = new LabRequestGuardFilter(LabPropertiesFixtures.withReadOnly(false, 1), mapper);
        for (int i = 0; i < 10; i++) {
            assertThat(call(filter, "GET", "/lab/status", "1.1.1.1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void nonLabPathsAreNeverTouched() throws Exception {
        LabRequestGuardFilter filter = new LabRequestGuardFilter(LabPropertiesFixtures.withReadOnly(true, 1), mapper);
        assertThat(call(filter, "POST", "/settlements", "1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void forwardedForIsIgnoredUnlessTrusted() throws Exception {
        LabRequestGuardFilter filter = new LabRequestGuardFilter(LabPropertiesFixtures.withReadOnly(false, 1), mapper);
        for (String spoof : new String[] {"5.5.5.5", "6.6.6.6"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/lab/x");
            req.setRemoteAddr("1.1.1.1");
            req.addHeader("X-Forwarded-For", spoof);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            if (spoof.equals("6.6.6.6")) {
                assertThat(res.getStatus()).as("spoofed header must not mint a fresh bucket").isEqualTo(429);
            }
        }
    }

    @Test
    void trustedForwardedForSeparatesVisitorsBehindTheIngress() throws Exception {
        LabProperties d = LabPropertiesFixtures.defaults();
        LabProperties trusting = new LabProperties(true, false, true, d.forbiddenHosts(), 1, 30, 60, d.load(), d.sse(), d.upstreams());
        LabRequestGuardFilter filter = new LabRequestGuardFilter(trusting, mapper);
        for (String visitor : new String[] {"5.5.5.5", "6.6.6.6"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/lab/x");
            req.setRemoteAddr("10.0.0.1");
            req.addHeader("X-Forwarded-For", visitor + ", 10.0.0.1");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus()).as(visitor).isEqualTo(200);
        }
    }
}
