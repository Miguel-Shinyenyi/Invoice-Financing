package com.settlementengine.core.lab;

import com.settlementengine.core.outbox.OutboxPublisher;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class LabKafkaIntegrationTest extends AbstractLabIntegrationTest {

    @Autowired
    LabKafkaService kafka;
    @Autowired
    LabResetService reset;
    @Autowired
    MockMvc mvc;
    @Autowired
    OutboxPublisher outbox;

    @BeforeEach
    void seed() {
        reset.reset();
    }

    private void settle() throws Exception {
        mvc.perform(post("/lab/settlements").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"" + UUID.randomUUID()
                        + "\",\"sourceAccountId\":\"10000000-0000-0000-0000-000000000001\","
                        + "\"destinationAccountId\":\"10000000-0000-0000-0000-000000000002\",\"amount\":1.00}"));
    }

    private LabKafkaService.Overview awaitConsumers() throws Exception {
        for (int i = 0; i < 120; i++) {
            LabKafkaService.Overview o = kafka.overview();
            boolean joined = o.topics().stream().anyMatch(t -> t.topic().equals("settlement.requested") && t.consumers() > 0);
            if (o.available() && joined) {
                return o;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("read-model consumers never joined");
    }

    @Test
    void theOverviewListsTheSixTopicsWithConsumerCountsAndZeroForTheMismatchTopic() throws Exception {
        settle();
        outbox.publishPending();
        LabKafkaService.Overview o = awaitConsumers();
        assertThat(o.topics()).extracting(LabKafkaService.TopicInfo::topic).containsExactlyInAnyOrderElementsOf(LabTopics.all());
        assertThat(o.topics().stream().filter(t -> t.topic().equals("reconciliation.mismatch_found")).findFirst().orElseThrow()
                .consumers()).isZero();
        assertThat(o.topics().stream().filter(t -> t.topic().equals("settlement.requested")).findFirst().orElseThrow()
                .consumers()).isGreaterThan(0);
    }

    @Test
    void consumerLagIsComputedPerPartitionForTheReadModelGroup() throws Exception {
        settle();
        outbox.publishPending();
        LabKafkaService.Overview o = awaitConsumers();
        var group = o.groups().stream().filter(g -> g.group().equals("settlement-engine-read-model")).findFirst().orElseThrow();
        assertThat(group.partitions()).isNotEmpty();
        group.partitions().forEach(p -> assertThat(p.lag()).isEqualTo(Math.max(0, p.endOffset() - p.committed())));
    }

    @Test
    void lastMessagesAreBoundedAndReadFromTheRealTopic() throws Exception {
        for (int i = 0; i < 3; i++) {
            settle();
        }
        outbox.publishPending();
        var messages = kafka.lastMessages("settlement.requested", 2);
        assertThat(messages).hasSizeLessThanOrEqualTo(2);
        assertThat(messages).isNotEmpty();
        assertThat(messages.get(0).value()).contains("settlementId");
        assertThat(kafka.lastMessages("settlement.requested", 100000)).hasSizeLessThanOrEqualTo(50);
    }

    @Test
    void anyTopicOutsideTheWhitelistIsRefused() {
        assertThatThrownBy(() -> kafka.lastMessages("__consumer_offsets", 10)).isInstanceOf(LabNotFoundException.class);
        assertThatThrownBy(() -> kafka.lastMessages("../../etc/passwd", 10)).isInstanceOf(LabNotFoundException.class);
        assertThatThrownBy(() -> kafka.lastMessages("settlement.requested,other", 10)).isInstanceOf(LabNotFoundException.class);
    }

    @Test
    void readingMessagesNeverMovesAnEngineGroupsOffsets() throws Exception {
        settle();
        outbox.publishPending();
        awaitConsumers();
        Thread.sleep(1500); // let the read model consumer commit what it has consumed
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            Map<TopicPartition, OffsetAndMetadata> before = admin.listConsumerGroupOffsets("settlement-engine-read-model")
                    .partitionsToOffsetAndMetadata().get();
            kafka.lastMessages("settlement.requested", 50);
            kafka.lastMessages("settlement.confirmed", 50);
            Map<TopicPartition, OffsetAndMetadata> after = admin.listConsumerGroupOffsets("settlement-engine-read-model")
                    .partitionsToOffsetAndMetadata().get();
            before.forEach((tp, off) -> assertThat(after.get(tp).offset()).isGreaterThanOrEqualTo(off.offset()));
            // the lab's throwaway reader groups are never committed anywhere
            assertThat(admin.listConsumerGroups().all().get().stream().map(g -> g.groupId()))
                    .noneMatch(id -> id.startsWith("lab-reader-"));
        }
    }

    @Test
    void theKafkaEndpointRefusesNonWhitelistedTopics() throws Exception {
        assertThat(mvc.perform(get("/lab/kafka/topics/__consumer_offsets/messages")).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(get("/lab/kafka")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
