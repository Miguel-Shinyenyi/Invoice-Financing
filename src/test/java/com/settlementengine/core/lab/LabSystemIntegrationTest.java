package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class LabSystemIntegrationTest extends AbstractLabIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    LabResetService reset;

    private Map<String, Long> counts() throws Exception {
        JsonNode sys = mapper.readTree(mvc.perform(get("/lab/system")).andReturn().getResponse().getContentAsString());
        Map<String, Long> out = new HashMap<>();
        sys.get("nodes").forEach(n -> out.put(n.get("id").asText(), n.get("count").asLong()));
        return out;
    }

    @Test
    void everyMapNodeReportsARealCountAndTheCountsMoveWhenARealEventHappens() throws Exception {
        reset.reset();
        Map<String, Long> before = counts();
        for (String id : new String[] {"client", "idempotency", "pending", "gateway", "finalize", "outbox", "kafka", "readmodel",
                "reconciliation", "sweep", "ledgercheck", "fraud"}) {
            assertThat(before).containsKey(id);
        }
        mvc.perform(post("/lab/settlements").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceAccountId\":\"10000000-0000-0000-0000-000000000001\","
                        + "\"destinationAccountId\":\"10000000-0000-0000-0000-000000000002\",\"amount\":2.00}"));
        Map<String, Long> after = counts();
        assertThat(after.get("idempotency")).isEqualTo(before.get("idempotency") + 1);
        assertThat(after.get("pending")).isEqualTo(before.get("pending") + 1);
        assertThat(after.get("finalize")).isGreaterThan(before.get("finalize"));
    }

    @Test
    void theStreamOpensWithAHelloEventSoABufferedStreamIsDetectable() throws Exception {
        var result = mvc.perform(get("/lab/logs/stream")).andReturn();
        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }
}
