package com.settlementengine.core.lab;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The filter wired into the real context: limit, then 429 with Retry-After, reads untouched. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "settlement-engine.demo.requests-per-minute-per-ip=3")
class LabRateLimitIntegrationTest extends AbstractLabIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void theFourthMutatingCallInAMinuteIs429ButReadsKeepWorking() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(mvc.perform(post("/lab/sweep/run")).andReturn().getResponse().getStatus()).isEqualTo(200);
        }
        var limited = mvc.perform(post("/lab/sweep/run").contentType(MediaType.APPLICATION_JSON)).andReturn().getResponse();
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotNull();
        assertThat(mvc.perform(get("/lab/status")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
