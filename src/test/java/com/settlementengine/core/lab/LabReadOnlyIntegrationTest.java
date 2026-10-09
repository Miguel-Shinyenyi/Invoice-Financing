package com.settlementengine.core.lab;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** settlement-engine.demo.read-only=true turns every mutating /lab endpoint into a 503. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "settlement-engine.demo.read-only=true")
class LabReadOnlyIntegrationTest extends AbstractLabIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void everyMutatingLabEndpointIs503WithAClearMessage() throws Exception {
        for (String path : new String[] {"/lab/settlements", "/lab/reset", "/lab/reconciliation/run", "/lab/sweep/run",
                "/lab/external/MOCK-SEED-1/forget", "/lab/personas/ADMIN/token", "/lab/load/start"}) {
            var res = mvc.perform(post(path)).andReturn().getResponse();
            assertThat(res.getStatus()).as(path).isEqualTo(503);
            assertThat(res.getContentAsString()).contains("read-only");
        }
    }

    @Test
    void readsStillWork() throws Exception {
        assertThat(mvc.perform(get("/lab/status")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
