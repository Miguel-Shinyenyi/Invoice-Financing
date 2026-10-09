package com.settlementengine.core.lab;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Read-only view of Kafka for the lab: partition offsets, per-group consumer lag, consumer counts, and the last
 * messages per topic. The topic list is fixed (KafkaTopics) and every read is bounded. Messages are read by a
 * lab-only consumer that uses {@code assign}, a throwaway group id and no commits, so it can never move an
 * engine group's offsets.
 */
@LabComponent
public class LabKafkaService {

    static final int MAX_MESSAGES = 50;
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    private static final String LAB_GROUP_PREFIX = "lab-reader-";

    private final String bootstrapServers;

    public LabKafkaService(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public record PartitionOffsets(int partition, long beginOffset, long endOffset) {
    }

    public record TopicInfo(String topic, List<PartitionOffsets> partitions, long messageCount, int consumers,
                            List<String> consumerGroups) {
    }

    public record GroupLag(String group, String state, List<PartitionLag> partitions, long totalLag) {
    }

    public record PartitionLag(String topic, int partition, long committed, long endOffset, long lag) {
    }

    public record Overview(boolean available, String error, List<TopicInfo> topics, List<GroupLag> groups, Instant at) {
    }

    public record Message(int partition, long offset, Instant timestamp, String key, String value) {
    }

    public Overview overview() {
        try (AdminClient admin = admin()) {
            Map<TopicPartition, Long> ends = new HashMap<>();
            Map<TopicPartition, Long> begins = new HashMap<>();
            List<String> existing = new ArrayList<>(admin.listTopics().names().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            List<String> topics = LabTopics.all().stream().filter(existing::contains).toList();
            Map<String, org.apache.kafka.clients.admin.TopicDescription> described =
                    admin.describeTopics(topics).allTopicNames().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
            described.forEach((t, d) -> d.partitions().forEach(p -> {
                latest.put(new TopicPartition(t, p.partition()), OffsetSpec.latest());
                earliest.put(new TopicPartition(t, p.partition()), OffsetSpec.earliest());
            }));
            if (!latest.isEmpty()) {
                for (var e : admin.listOffsets(latest).all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).entrySet()) {
                    ends.put(e.getKey(), e.getValue().offset());
                }
                for (var e : admin.listOffsets(earliest).all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).entrySet()) {
                    begins.put(e.getKey(), e.getValue().offset());
                }
            }

            // groups, excluding the lab's own throwaway readers
            List<String> groupIds = admin.listConsumerGroups().all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).stream()
                    .map(ConsumerGroupListing::groupId).filter(g -> !g.startsWith(LAB_GROUP_PREFIX)).sorted().toList();
            Map<String, ConsumerGroupDescription> descriptions = groupIds.isEmpty() ? Map.of()
                    : admin.describeConsumerGroups(groupIds).all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            Map<String, Set<String>> groupsByTopic = new HashMap<>();
            Map<String, Integer> consumersByTopic = new HashMap<>();
            List<GroupLag> groups = new ArrayList<>();
            for (String groupId : groupIds) {
                ConsumerGroupDescription d = descriptions.get(groupId);
                for (MemberDescription m : d.members()) {
                    Set<String> memberTopics = new HashSet<>();
                    m.assignment().topicPartitions().forEach(tp -> memberTopics.add(tp.topic()));
                    for (String t : memberTopics) {
                        consumersByTopic.merge(t, 1, Integer::sum);
                        groupsByTopic.computeIfAbsent(t, k -> new HashSet<>()).add(groupId);
                    }
                }
                Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(groupId)
                        .partitionsToOffsetAndMetadata().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                List<PartitionLag> lags = new ArrayList<>();
                long total = 0;
                for (var e : committed.entrySet()) {
                    Long end = ends.get(e.getKey());
                    if (end == null) {
                        continue; // not one of the engine's topics
                    }
                    long lag = Math.max(0, end - e.getValue().offset());
                    total += lag;
                    lags.add(new PartitionLag(e.getKey().topic(), e.getKey().partition(), e.getValue().offset(), end, lag));
                }
                lags.sort(Comparator.comparing(PartitionLag::topic).thenComparingInt(PartitionLag::partition));
                if (!lags.isEmpty() || !d.members().isEmpty()) {
                    groups.add(new GroupLag(groupId, d.state().toString(), lags, total));
                }
            }

            List<TopicInfo> infos = new ArrayList<>();
            for (String topic : LabTopics.all()) {
                List<PartitionOffsets> parts = new ArrayList<>();
                long count = 0;
                for (var e : ends.entrySet()) {
                    if (e.getKey().topic().equals(topic)) {
                        long begin = begins.getOrDefault(e.getKey(), 0L);
                        parts.add(new PartitionOffsets(e.getKey().partition(), begin, e.getValue()));
                        count += e.getValue() - begin;
                    }
                }
                parts.sort(Comparator.comparingInt(PartitionOffsets::partition));
                infos.add(new TopicInfo(topic, parts, count, consumersByTopic.getOrDefault(topic, 0),
                        groupsByTopic.getOrDefault(topic, Set.of()).stream().sorted().toList()));
            }
            return new Overview(true, null, infos, groups, Instant.now());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Overview(false, "Kafka unreachable or slow: " + e.getClass().getSimpleName(),
                    LabTopics.all().stream().map(t -> new TopicInfo(t, List.of(), 0, 0, List.of())).toList(), List.of(),
                    Instant.now());
        }
    }

    /** The last {@code limit} (at most 50) messages of one whitelisted topic. */
    public List<Message> lastMessages(String topic, int limit) {
        if (!LabTopics.isKnown(topic)) {
            throw new LabNotFoundException("Not one of the engine's topics: " + topic);
        }
        int wanted = Math.max(1, Math.min(MAX_MESSAGES, limit));
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, LAB_GROUP_PREFIX + UUID.randomUUID());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
        p.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "4000");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "200");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, TIMEOUT).stream()
                    .map(i -> new TopicPartition(topic, i.partition())).toList();
            consumer.assign(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, TIMEOUT);
            Map<TopicPartition, Long> begins = consumer.beginningOffsets(partitions, TIMEOUT);
            Map<TopicPartition, Long> stopAt = new LinkedHashMap<>();
            for (TopicPartition tp : partitions) {
                long end = ends.get(tp);
                long start = Math.max(begins.get(tp), end - wanted);
                if (end > start) {
                    consumer.seek(tp, start);
                    stopAt.put(tp, end);
                }
            }
            List<Message> out = new ArrayList<>();
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            Set<TopicPartition> done = new HashSet<>();
            while (done.size() < stopAt.size() && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(300))) {
                    TopicPartition tp = new TopicPartition(r.topic(), r.partition());
                    if (r.offset() < stopAt.get(tp)) {
                        out.add(new Message(r.partition(), r.offset(), Instant.ofEpochMilli(r.timestamp()), r.key(),
                                truncate(r.value())));
                    }
                    if (r.offset() >= stopAt.get(tp) - 1) {
                        done.add(tp);
                    }
                }
            }
            out.sort(Comparator.comparing(Message::timestamp).thenComparingLong(Message::offset));
            return out.size() > wanted ? out.subList(out.size() - wanted, out.size()) : out;
        } catch (RuntimeException e) {
            throw new LabBusyException("Kafka did not answer in time; try again.");
        }
    }

    private AdminClient admin() {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");
        return AdminClient.create(p);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 2000 ? value : value.substring(0, 2000) + "...";
    }
}
