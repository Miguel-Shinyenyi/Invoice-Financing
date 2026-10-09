package com.settlementengine.core.lab;

import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.gateway.MockExternalSystem;
import com.settlementengine.core.service.CreateSettlementCommand;
import com.settlementengine.core.service.RequestHasher;
import com.settlementengine.core.service.SettlementResult;
import com.settlementengine.core.service.SettlementService;
import com.settlementengine.core.service.SettlementTransactions;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Creates settlements through the REAL {@link SettlementService}, optionally under an injected gateway fault. */
@LabComponent
public class LabPlaygroundService {

    static final String STRANDED_EXPLANATION = "UNKNOWN with no external reference. Reconciliation only loads "
            + "settlements that have one (findByExternalRefIsNotNull), so nothing will ever pick this up or open a "
            + "mismatch for it. Known gap 1.";

    private static final List<String> STEP_ORDER = List.of("IDEMPOTENCY_KEY_WRITTEN", "PENDING_ROW", "GATEWAY_CALL",
            "FINALIZE", "LEDGER_ENTRIES", "OUTBOX_EVENTS");

    private final SettlementService settlementService;
    private final SettlementTransactions settlementTransactions;
    private final RequestHasher requestHasher;
    private final LabInspector inspector;
    private final LabProperties props;
    private final LabOrphanedExternalRecords orphans;
    private final MockExternalSystem external;
    private final JdbcTemplate jdbc;
    private final long staleGraceSeconds;

    public LabPlaygroundService(SettlementService settlementService, SettlementTransactions settlementTransactions,
                                RequestHasher requestHasher, LabInspector inspector, LabProperties props,
                                LabOrphanedExternalRecords orphans, MockExternalSystem external, JdbcTemplate jdbc,
                                @Value("${settlement-engine.reconciliation.stale-pending-grace-period-seconds:300}") long staleGraceSeconds) {
        this.settlementService = settlementService;
        this.settlementTransactions = settlementTransactions;
        this.requestHasher = requestHasher;
        this.inspector = inspector;
        this.props = props;
        this.orphans = orphans;
        this.external = external;
        this.jdbc = jdbc;
        this.staleGraceSeconds = staleGraceSeconds;
    }

    public PlaygroundResponse create(PlaygroundRequest req) {
        CreateSettlementCommand command = validate(req);
        UUID key = req.idempotencyKey() != null ? req.idempotencyKey() : UUID.randomUUID();
        LabFaultPlan plan = planFor(req.fault());
        boolean replay = settlementTransactions.findExisting(key).isPresent();

        SettlementResult result = null;
        String error = null;
        int httpStatus = HttpStatus.CREATED.value();
        try {
            result = LabFaultContext.with(plan, () -> settlementService.createSettlement(key, command));
        } catch (RuntimeException ex) {
            HttpStatus mapped = LabHttpStatus.statusFor(ex);
            if (mapped == null) {
                throw ex;
            }
            httpStatus = mapped.value();
            error = ex.getMessage();
        }

        UUID settlementId = result != null ? result.settlementId() : settlementIdForKey(key);
        boolean stranded = result != null && result.status() == SettlementStatus.UNKNOWN && result.externalRef() == null;
        String orphanRef = settlementId == null ? null : orphans.all().stream()
                .filter(o -> o.settlementId().equals(settlementId))
                .map(LabOrphanedExternalRecords.Orphan::externalRef).findFirst().orElse(null);
        boolean held = orphanRef != null && external.findByReference(orphanRef).isPresent();

        List<LabStep> steps = settlementId == null ? List.of() : buildSteps(settlementId, replay, httpStatus, error);
        return new PlaygroundResponse(key, replay && result != null, httpStatus, result, error,
                plan.fault().name(), settlementId, stranded, stranded ? STRANDED_EXPLANATION : null, held, orphanRef,
                steps, MDC.get("requestId"));
    }

    /** Leaves exactly the database state a hard crash between the two transactions leaves. */
    public OrphanResponse orphan(PlaygroundRequest req) {
        CreateSettlementCommand command = validate(req);
        UUID key = req.idempotencyKey() != null ? req.idempotencyKey() : UUID.randomUUID();
        var executionRequest = settlementTransactions.createPendingSettlement(key, requestHasher.hash(command), command);
        Instant sweepAfter = Instant.now().plus(Duration.ofSeconds(staleGraceSeconds));
        return new OrphanResponse(key, executionRequest.settlementId(), "PENDING", sweepAfter,
                "createPendingSettlement committed and the process 'died' before the gateway call. The idempotency key "
                        + "is IN_PROGRESS, so retries with this key get 409 until the stale-pending sweep finalizes the "
                        + "settlement as UNKNOWN after " + staleGraceSeconds + "s.");
    }

    public record OrphanResponse(UUID idempotencyKey, UUID settlementId, String status, Instant sweepEligibleAt,
                                 String explanation) {
    }

    private CreateSettlementCommand validate(PlaygroundRequest req) {
        if (req == null || req.sourceAccountId() == null || req.destinationAccountId() == null) {
            throw new LabValidationException("sourceAccountId and destinationAccountId are required");
        }
        BigDecimal amount = req.amount();
        LabProperties.Load caps = props.load();
        if (amount == null || amount.scale() > 2 || amount.compareTo(caps.minAmount()) < 0
                || amount.compareTo(caps.maxAmount()) > 0) {
            throw new LabValidationException("amount must be between " + caps.minAmount() + " and " + caps.maxAmount()
                    + " with at most 2 decimals");
        }
        String currency = req.currency() == null || req.currency().isBlank() ? "USD" : req.currency();
        if (!currency.matches("[A-Z]{3}")) {
            throw new LabValidationException("currency must be a 3-letter code");
        }
        return new CreateSettlementCommand(req.sourceAccountId(), req.destinationAccountId(), amount, currency);
    }

    private static LabFaultPlan planFor(PlaygroundRequest.FaultRequest fault) {
        if (fault == null || fault.mode() == null || fault.mode() == LabFault.NONE) {
            return LabFaultPlan.of(LabFault.NONE);
        }
        if (fault.mode() == LabFault.SLOW) {
            return LabFaultPlan.slow(fault.slowMs() == null ? 500 : fault.slowMs());
        }
        return LabFaultPlan.of(fault.mode());
    }

    private UUID settlementIdForKey(UUID key) {
        List<UUID> ids = jdbc.queryForList("select id from settlements where idempotency_key = ?", UUID.class, key);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<LabStep> buildSteps(UUID settlementId, boolean replay, int httpStatus, String error) {
        LabInspection in = inspector.inspect(settlementId);
        List<LabStep> steps = new ArrayList<>();
        Map<String, Object> key = in.idempotencyKey();
        Map<String, Object> st = in.settlement();
        if (replay) {
            steps.add(new LabStep(0, "IDEMPOTENCY_KEY_FOUND", "Idempotency key already known", at(key.get("created_at")),
                    httpStatus == 409 ? "Conflict: " + error
                            : "Stored response returned. No gateway call, no new rows, no money moved.", "database"));
            return number(steps);
        }
        steps.add(new LabStep(0, "IDEMPOTENCY_KEY_WRITTEN", "Idempotency key written", at(key.get("created_at")),
                "key " + key.get("key") + " claimed (IN_PROGRESS) in the same transaction as the pending row", "database"));
        steps.add(new LabStep(0, "PENDING_ROW", "Settlement row created as PENDING", at(st.get("created_at")),
                "settlement " + settlementId + ", " + st.get("amount") + " " + st.get("currency")
                        + ", plus a settlement.requested outbox event", "database"));
        in.logs().stream().filter(e -> e.message() != null && e.message().startsWith("Gateway call:")).findFirst()
                .ifPresent(e -> steps.add(new LabStep(0, "GATEWAY_CALL", "External gateway called", e.timestamp(),
                        e.message().substring("Gateway call: ".length()), "log")));
        steps.add(new LabStep(0, "FINALIZE", "Finalized as " + st.get("status"), at(st.get("updated_at")),
                "externalRef=" + st.get("external_ref") + "; idempotency key now " + key.get("status"), "database"));
        if (!in.ledgerEntries().isEmpty()) {
            steps.add(new LabStep(0, "LEDGER_ENTRIES", "Ledger entries written", at(in.ledgerEntries().get(0).get("created_at")),
                    in.ledgerEntries().stream().map(e -> e.get("entry_type") + " " + e.get("amount") + " on account "
                            + e.get("account_id")).reduce((a, b) -> a + "; " + b).orElse(""), "database"));
        }
        if (!in.outboxEvents().isEmpty()) {
            steps.add(new LabStep(0, "OUTBOX_EVENTS", "Outbox events written", at(in.outboxEvents().get(in.outboxEvents().size() - 1).get("created_at")),
                    in.outboxEvents().stream().map(e -> String.valueOf(e.get("topic"))).reduce((a, b) -> a + ", " + b).orElse(""),
                    "database"));
        }
        steps.sort(Comparator.comparingInt(s -> STEP_ORDER.indexOf(s.key())));
        return number(steps);
    }

    private static List<LabStep> number(List<LabStep> steps) {
        List<LabStep> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            LabStep s = steps.get(i);
            out.add(new LabStep(i + 1, s.key(), s.title(), s.at(), s.detail(), s.source()));
        }
        return out;
    }

    private static Instant at(Object timestamp) {
        if (timestamp instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (timestamp instanceof java.time.OffsetDateTime o) {
            return o.toInstant();
        }
        return timestamp == null ? null : Instant.parse(timestamp.toString());
    }
}
