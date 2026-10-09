package com.settlementengine.core.lab;

import com.settlementengine.core.gateway.MockExternalSystem;
import com.settlementengine.core.invoicing.MockInvoicePaymentSource;
import com.settlementengine.core.reconciliation.ExternalRecord;
import com.settlementengine.core.reconciliation.ExternalStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Returns the sandbox to the committed seed: in ONE transaction, truncate the data tables, reload
 * {@code lab/seed/lab-seed.sql}, and rebuild the in-memory external record store to agree with the
 * seeded settlements. Runs on startup, on a schedule, and on demand.
 */
@LabComponent
public class LabResetService implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(LabResetService.class);

    /** Children before parents, though a single TRUNCATE statement listing all of them does not need the order. */
    static final List<String> DATA_TABLES = List.of(
            "lab_load_runs", "refresh_tokens", "audit_log", "fraud_assessments", "advances", "invoices",
            "ledger_mismatches", "reconciliation_mismatches", "reconciliation_runs", "settlement_read_model",
            "outbox_events", "ledger_entries", "settlements", "idempotency_keys", "ledger_accounts", "users");

    /** The seeded external record for this settlement deliberately disagrees (the open mismatch). */
    static final String SEEDED_DISAGREEING_REF = "MOCK-SEED-11";
    static final BigDecimal SEEDED_DISAGREEING_AMOUNT = new BigDecimal("99.00");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MockExternalSystem externalSystem;
    private final MockInvoicePaymentSource paymentSource;
    private final List<LabResettable> resettables;
    private final LabProperties props;
    private final LoadRunGate loadRunGate;
    private volatile LabResetState state;
    private volatile Instant lastRequestedAt = Instant.EPOCH;

    public LabResetService(DataSource dataSource, JdbcTemplate jdbc, PlatformTransactionManager txManager,
                           MockExternalSystem externalSystem, MockInvoicePaymentSource paymentSource,
                           List<LabResettable> resettables, LabProperties props, LoadRunGate loadRunGate) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.externalSystem = externalSystem;
        this.paymentSource = paymentSource;
        this.resettables = resettables;
        this.props = props;
        this.loadRunGate = loadRunGate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        reset();
    }

    @Scheduled(fixedDelayString = "${settlement-engine.demo.auto-reset-minutes:30}",
            initialDelayString = "${settlement-engine.demo.auto-reset-minutes:30}", timeUnit = TimeUnit.MINUTES)
    public void scheduledReset() {
        if (loadRunGate.activeRunId() != null) {
            log.info("Skipping scheduled lab reset: a load run is in progress");
            return;
        }
        reset();
    }

    /** The on-demand path. Throws {@link LabBusyException} if called again inside the cooldown. */
    public synchronized LabResetState resetOnDemand() {
        Instant now = Instant.now();
        Duration cooldown = Duration.ofSeconds(props.resetCooldownSeconds());
        Instant allowedAt = lastRequestedAt.plus(cooldown);
        if (now.isBefore(allowedAt)) {
            throw new LabBusyException("Reset is limited to once per " + props.resetCooldownSeconds()
                    + " seconds; try again in " + Duration.between(now, allowedAt).toSeconds() + "s.");
        }
        if (loadRunGate.activeRunId() != null) {
            throw new LabBusyException("A load run is in progress; reset after it finishes.");
        }
        lastRequestedAt = now;
        return reset();
    }

    public synchronized LabResetState reset() {
        tx.executeWithoutResult(status -> {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", DATA_TABLES));
            loadSeed();
            externalSystem.replaceAll(externalRecordsForSeededSettlements());
        });
        paymentSource.clear();
        resettables.forEach(LabResettable::resetInMemory);
        Instant now = Instant.now();
        state = new LabResetState(now, now.plus(Duration.ofMinutes(props.autoResetMinutes())));
        log.info("Lab reset to seed at {}", now);
        return state;
    }

    public LabResetState state() {
        return state;
    }

    private void loadSeed() {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource("lab/lab-seed.sql"), "UTF-8"));
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private List<ExternalRecord> externalRecordsForSeededSettlements() {
        List<ExternalRecord> records = new ArrayList<>();
        jdbc.query("select external_ref, amount, currency, status from settlements where external_ref is not null", rs -> {
            String ref = rs.getString("external_ref");
            BigDecimal amount = ref.equals(SEEDED_DISAGREEING_REF) ? SEEDED_DISAGREEING_AMOUNT : rs.getBigDecimal("amount");
            ExternalStatus status = "FAILED".equals(rs.getString("status")) ? ExternalStatus.FAILED : ExternalStatus.CONFIRMED;
            records.add(new ExternalRecord(ref, amount, rs.getString("currency"), status));
        });
        return records;
    }
}
