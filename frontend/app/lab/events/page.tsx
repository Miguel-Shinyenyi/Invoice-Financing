"use client";

import { useState } from "react";
import { ShowRequest } from "@/components/lab/ShowRequest";
import { Btn, Card, CodeBox, KnownGap, LiveBadge, Notice, PageHeader, Stat, Table } from "@/components/ui";
import { lab, type LabResult } from "@/lib/lab/client";
import { fmtTime, shortId } from "@/lib/lab/format";
import { useLabJson } from "@/lib/lab/useLabJson";

interface TopicRow { topic: string; pending: number; published: number; consumers: number | null; consumerGroups: string[]; kafkaMessageCount: number | null; knownGap?: string }
interface Recent { id: string; topic: string; aggregateType: string; aggregateId: string; createdAt: string; publishedAt: string | null; publishLagMs: number | null }
interface EventsView { topics: TopicRow[]; recent: Recent[]; readModel: { writeSide: number; readSide: number; lag: number }; kafkaAvailable: boolean }
interface KafkaView {
  available: boolean;
  error: string | null;
  topics: Array<{ topic: string; partitions: Array<{ partition: number; beginOffset: number; endOffset: number }>; messageCount: number }>;
  groups: Array<{ group: string; state: string; totalLag: number; partitions: Array<{ topic: string; partition: number; committed: number; endOffset: number; lag: number }> }>;
}
interface Message { partition: number; offset: number; timestamp: string; key: string | null; value: string }
interface OutOfOrder { settlementId: string; published: Array<{ order: number; topic: string; occurredAt: string }>; afterFirstEvent: string | null; afterSecondEvent: string | null; lastWriteWinsHeld: boolean; explanation: string }

export default function EventsPage() {
  const { data: events, mode } = useLabJson<EventsView>("events", { limit: 15 }, 1000);
  const { data: kafka } = useLabJson<KafkaView>("kafka", undefined, 2000);
  const [topic, setTopic] = useState("settlement.requested");
  const [messages, setMessages] = useState<LabResult<Message[]> | null>(null);
  const [demo, setDemo] = useState<LabResult<OutOfOrder> | null>(null);
  const [busy, setBusy] = useState(false);

  return (
    <div className="space-y-5">
      <PageHeader title="Events and Kafka" subtitle="Outbox rows, the six topics with their message and consumer counts, per-group consumer lag, and the read model's lag behind the write side.">
        <LiveBadge mode={mode} />
      </PageHeader>

      {events && (
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          <Stat label="Write side" value={events.readModel.writeSide} hint="settlements" />
          <Stat label="Read model" value={events.readModel.readSide} hint="projected rows" />
          <Stat label="Projection lag" value={events.readModel.lag} tone={events.readModel.lag > 0 ? "yellow" : "default"} />
          <Stat label="Kafka" value={events.kafkaAvailable ? "reachable" : "unreachable"} />
        </div>
      )}

      <Card title="Topics (the six in KafkaTopics)">
        <Table head={["Topic", "Outbox pending", "Outbox published", "Kafka messages", "Consumers", "Consumer groups"]}>
          {(events?.topics ?? []).map((t) => (
            <tr key={t.topic} data-topic={t.topic} data-consumers={t.consumers ?? ""}>
              <td className="px-3 py-1.5 font-mono text-xs font-semibold">{t.topic}</td>
              <td className="px-3 py-1.5 font-mono">{t.pending}</td>
              <td className="px-3 py-1.5 font-mono">{t.published}</td>
              <td className="px-3 py-1.5 font-mono">{t.kafkaMessageCount ?? "—"}</td>
              <td className="px-3 py-1.5 font-mono">
                {t.consumers ?? "—"}
                {t.knownGap && <div className="mt-1"><KnownGap n={6} anchor="current-state" doc="docs/kafka-events.md" /></div>}
              </td>
              <td className="px-3 py-1.5 text-xs">{t.consumerGroups.join(", ") || "none"}</td>
            </tr>
          ))}
        </Table>
        <p className="mt-2 text-xs font-medium">reconciliation.mismatch_found has zero consumers: nothing alerts a human about a mismatch.</p>
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Consumer lag (sandbox only)">
          {kafka && !kafka.available && <Notice tone="yellow">{kafka.error}</Notice>}
          <Table head={["Group", "Topic / partition", "Committed", "End", "Lag"]} empty={kafka && kafka.groups.length === 0 ? "No consumer groups yet." : undefined}>
            {(kafka?.groups ?? []).flatMap((g) => g.partitions.map((p) => (
              <tr key={`${g.group}-${p.topic}-${p.partition}`}>
                <td className="px-3 py-1.5 font-mono text-xs">{g.group}</td>
                <td className="px-3 py-1.5 font-mono text-xs">{p.topic}[{p.partition}]</td>
                <td className="px-3 py-1.5 font-mono">{p.committed}</td>
                <td className="px-3 py-1.5 font-mono">{p.endOffset}</td>
                <td className="px-3 py-1.5 font-mono font-semibold">{p.lag}</td>
              </tr>
            )))}
          </Table>
          <p className="mt-2 text-xs font-medium">Computed from the group&apos;s committed offsets and each partition&apos;s end offset. Staging does not expose consumer lag (see observability.md); this is sandbox-only.</p>
        </Card>
        <Card title="Partition offsets">
          <Table head={["Topic", "Partition", "Begin", "End"]}>
            {(kafka?.topics ?? []).flatMap((t) => t.partitions.map((p) => (
              <tr key={`${t.topic}-${p.partition}`}><td className="px-3 py-1.5 font-mono text-xs">{t.topic}</td><td className="px-3 py-1.5 font-mono">{p.partition}</td><td className="px-3 py-1.5 font-mono">{p.beginOffset}</td><td className="px-3 py-1.5 font-mono">{p.endOffset}</td></tr>
            )))}
          </Table>
        </Card>
      </div>

      <Card title="Last 50 messages on a topic">
        <p className="mb-2 text-xs font-medium">Read by a lab-only consumer with its own throwaway group id that never commits, so it cannot move an engine group&apos;s offsets.</p>
        <div className="flex flex-wrap gap-2">
          <select aria-label="Topic" className="neo-input min-w-0 flex-1" value={topic} onChange={(e) => setTopic(e.target.value)}>
            {(events?.topics ?? []).map((t) => <option key={t.topic}>{t.topic}</option>)}
          </select>
          <Btn tone="yellow" onClick={async () => setMessages(await lab.get<Message[]>(`kafka/topics/${topic}/messages`, { limit: 50 }))}>Read</Btn>
        </div>
        {messages && !messages.ok && <div className="mt-2"><Notice tone="red">{messages.message}</Notice></div>}
        {messages?.data && (
          <div className="mt-2"><CodeBox>
            {messages.data.length === 0 ? "No messages on this topic." : messages.data.map((m) => `[${m.partition}:${m.offset}] ${fmtTime(m.timestamp)} key=${shortId(m.key)}  ${m.value}`).join("\n")}
          </CodeBox></div>
        )}
        <div className="mt-2"><ShowRequest sent={messages?.sent ?? null} /></div>
      </Card>

      <Card title="Recent outbox rows">
        <Table head={["Topic", "Aggregate", "Created", "Published", "Publish lag"]}>
          {(events?.recent ?? []).map((r) => (
            <tr key={r.id}><td className="px-3 py-1.5 font-mono text-xs">{r.topic}</td><td className="px-3 py-1.5 font-mono text-xs">{r.aggregateType} {shortId(r.aggregateId)}</td><td className="px-3 py-1.5 font-mono text-xs">{fmtTime(r.createdAt)}</td><td className="px-3 py-1.5 font-mono text-xs">{r.publishedAt ? fmtTime(r.publishedAt) : "not yet"}</td><td className="px-3 py-1.5 font-mono text-xs">{r.publishLagMs === null ? "—" : `${r.publishLagMs} ms`}</td></tr>
          ))}
        </Table>
      </Card>

      <Card title="Out-of-order delivery (optional demo)">
        <p className="mb-2 text-xs font-medium">Kafka orders messages only within a partition. This publishes settlement.confirmed before settlement.requested for a synthetic settlement, through the real producer, and shows the read model&apos;s last-write-wins check holding.</p>
        <Btn busy={busy} onClick={async () => { setBusy(true); setDemo(await lab.post<OutOfOrder>("events/out-of-order")); setBusy(false); }}>Publish out of order</Btn>
        {demo?.data && (
          <div className="mt-3 space-y-2 text-sm">
            <Notice tone={demo.data.lastWriteWinsHeld ? "green" : "red"} title={demo.data.lastWriteWinsHeld ? "Held." : "Did not hold."}>
              After the confirmed event the read model said {demo.data.afterFirstEvent}; after the earlier requested event arrived it still said {demo.data.afterSecondEvent}.
            </Notice>
            <p className="text-xs font-medium">{demo.data.explanation}</p>
          </div>
        )}
        {demo && !demo.ok && <div className="mt-2"><Notice tone="red">{demo.message}</Notice></div>}
        <div className="mt-2"><ShowRequest sent={demo?.sent ?? null} /></div>
      </Card>
    </div>
  );
}
