package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Runs the invoice and fraud scenarios against the REAL ml-service image, not a stub. */
@AutoConfigureMockMvc
class LabInvoiceIntegrationTest extends AbstractLabIntegrationTest {

    static final GenericContainer<?> ML = new GenericContainer<>(new ImageFromDockerfile()
            .withFileFromPath(".", Path.of("ml-service")))
            .withExposedPorts(8000)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200));

    static {
        ML.start();
    }

    @DynamicPropertySource
    static void ml(DynamicPropertyRegistry registry) {
        registry.add("settlement-engine.fraud-detection.base-url", () -> "http://" + ML.getHost() + ":" + ML.getMappedPort(8000));
        registry.add("settlement-engine.fraud-detection.timeout-ms", () -> "5000");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    LabResetService reset;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void freshSeed() {
        reset.reset();
    }

    private JsonNode run(String scenario) throws Exception {
        var res = mvc.perform(post("/lab/invoices/demo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"" + scenario + "\"}")).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return mapper.readTree(res.getContentAsString());
    }

    @Test
    void aCleanInvoiceIsAllowedWithScoreZeroAndNoReasons() throws Exception {
        JsonNode out = run("CLEAN");
        assertThat(out.get("assessment").get("decision").asText()).isEqualTo("ALLOW");
        assertThat(out.get("assessment").get("score").asDouble()).isZero();
        assertThat(out.get("assessment").get("reasons")).isEmpty();
        assertThat(out.get("invoiceStatus").asText()).isEqualTo("FINANCED");
        assertThat(out.get("advance").get("amountAdvanced").asDouble()).isEqualTo(320.0);
    }

    @Test
    void aDuplicateCustomerReferenceScoresPoint4AndIsStillAllowed() throws Exception {
        JsonNode out = run("DUPLICATE_REFERENCE");
        assertThat(out.get("assessment").get("score").asDouble()).isEqualTo(0.4);
        assertThat(out.get("assessment").get("decision").asText()).isEqualTo("ALLOW");
        assertThat(out.get("assessment").get("reasons").get(0).asText()).isEqualTo("duplicate_customer_reference");
        assertThat(out.get("inputsSent").get("duplicate_customer_reference_count").asInt()).isEqualTo(1);
    }

    @Test
    void aNewAccountWithAHighAdvanceScoresPoint3() throws Exception {
        JsonNode out = run("NEW_ACCOUNT_HIGH_ADVANCE");
        assertThat(out.get("assessment").get("score").asDouble()).isEqualTo(0.3);
        assertThat(out.get("assessment").get("reasons").get(0).asText()).isEqualTo("new_account_high_advance");
        assertThat(out.get("assessment").get("decision").asText()).isEqualTo("ALLOW");
    }

    @Test
    void rapidRefinancingScoresPoint3AfterThreeOutstandingAdvances() throws Exception {
        JsonNode out = run("RAPID_REFINANCING");
        assertThat(out.get("assessment").get("score").asDouble()).isEqualTo(0.3);
        assertThat(out.get("assessment").get("reasons").get(0).asText()).isEqualTo("rapid_refinancing");
        assertThat(out.get("inputsSent").get("outstanding_advance_count").asInt()).isEqualTo(3);
        assertThat(out.get("setup")).hasSize(3);
    }

    @Test
    void theCombinationCrossesTheBlockThresholdAndNothingIsDisbursed() throws Exception {
        JsonNode out = run("BLOCKED_COMBINATION");
        assertThat(out.get("assessment").get("score").asDouble()).isEqualTo(0.7);
        assertThat(out.get("assessment").get("decision").asText()).isEqualTo("BLOCK");
        assertThat(out.get("httpStatus").asInt()).isEqualTo(422);
        assertThat(out.get("invoiceStatus").asText()).isEqualTo("ISSUED");
        assertThat(out.get("advance").isNull()).isTrue();
    }

    @Test
    void markPaidThenARepaymentRunRepaysTheInvoice() throws Exception {
        JsonNode out = run("CLEAN");
        String invoiceId = out.get("invoiceId").asText();
        assertThat(mvc.perform(post("/lab/invoices/" + invoiceId + "/mark-paid")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(post("/lab/invoices/repayment-run")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from invoices where id = ?::uuid", String.class, invoiceId)).isEqualTo("REPAID");
    }

    @Test
    void aPaymentOfTheWrongAmountIsLeftForReview() throws Exception {
        JsonNode out = run("CLEAN");
        String invoiceId = out.get("invoiceId").asText();
        mvc.perform(post("/lab/invoices/" + invoiceId + "/mark-paid").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":399.00}"));
        mvc.perform(post("/lab/invoices/repayment-run"));
        assertThat(jdbc.queryForObject("select status from invoices where id = ?::uuid", String.class, invoiceId)).isEqualTo("FINANCED");
    }

    @Test
    void unknownScenarioIs400AndUnknownInvoiceIs404() throws Exception {
        assertThat(mvc.perform(post("/lab/invoices/demo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"NOPE\"}")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(post("/lab/invoices/" + UUID.randomUUID() + "/mark-paid")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}
