package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class LabOutOfOrderIntegrationTest extends AbstractLabIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LabResetService reset;

    @Test
    void aConfirmedEventPublishedBeforeItsRequestedEventStillLeavesTheReadModelConfirmed() throws Exception {
        reset.reset();
        MvcResult r = mvc.perform(post("/lab/events/out-of-order")).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode out = mapper.readTree(r.getResponse().getContentAsString());

        assertThat(out.get("published").get(0).get("topic").asText()).isEqualTo("settlement.confirmed");
        assertThat(out.get("published").get(1).get("topic").asText()).isEqualTo("settlement.requested");
        assertThat(out.get("afterFirstEvent").asText()).isEqualTo("CONFIRMED");
        assertThat(out.get("afterSecondEvent").asText()).isEqualTo("CONFIRMED");
        assertThat(out.get("lastWriteWinsHeld").asBoolean()).isTrue();
        assertThat(out.get("explanation").asText()).contains("occurredAt");
    }

    @Test
    void theDemoCleansUpItsSyntheticReadModelRowSoLagStaysHonest() throws Exception {
        reset.reset();
        int before = jdbc.queryForObject("select count(*) from settlement_read_model", Integer.class);
        mvc.perform(post("/lab/events/out-of-order"));
        assertThat(jdbc.queryForObject("select count(*) from settlement_read_model", Integer.class)).isEqualTo(before);
    }
}
