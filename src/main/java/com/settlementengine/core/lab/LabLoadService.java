package com.settlementengine.core.lab;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Validates, gates, runs and reports load plans. One run at a time, within the configured caps. */
@LabComponent
public class LabLoadService implements LabResettable {

    private static final Logger log = LoggerFactory.getLogger(LabLoadService.class);

    static final String STRANDED_EXPLANATION = "These settlements ended UNKNOWN with no external reference "
            + "(an injected fault, or finalize retries exhausted). Reconciliation only loads settlements that have a "
            + "reference, so nothing will resolve them. Known gap 1. Expected under fault injection, not an invariant failure.";

    private static final class Run {
        final UUID id = UUID.randomUUID();
        final LoadPlan plan;
        final Instant startedAt = Instant.now();
        final LabLoadStats stats = new LabLoadStats();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final List<LabLoadSample> samples = new CopyOnWriteArrayList<>();
        final List<Consumer<LabLoadSample>> listeners = new CopyOnWriteArrayList<>();
        final List<Runnable> completionListeners = new CopyOnWriteArrayList<>();
        final UUID burstKey = UUID.randomUUID();
        volatile String status = "RUNNING";
        volatile Instant finishedAt;
        volatile List<LabInvariant> invariants = List.of();
        volatile long retriesBase;
        volatile long fallbackBase;

        Run(LoadPlan plan) {
            this.plan = plan;
        }
    }

    private final LabProperties props;
    private final LoadRunGate gate;
    private final LabLoadInvariants invariants;
    private final LabPersonaService personas;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final MeterRegistry meterRegistry;
    private final Environment env;
    private final ObjectMapper mapper;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lab-load-coordinator");
        t.setDaemon(true);
        return t;
    });
    private volatile UUID activeRun;
    private volatile LabFaultProfile activeProfile;

    public LabLoadService(LabProperties props, LoadRunGate gate, LabLoadInvariants invariants, LabPersonaService personas,
                          JdbcTemplate jdbc, DataSource dataSource, MeterRegistry meterRegistry, Environment env,
                          ObjectMapper mapper) {
        this.props = props;
        this.gate = gate;
        this.invariants = invariants;
        this.personas = personas;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.meterRegistry = meterRegistry;
        this.env = env;
        this.mapper = mapper;
    }

    public LabLoadRunView start(LoadPlan plan) {
        LoadPlanValidator.validate(plan, props.load());
        if (plan.scenario() == LoadScenario.DUPLICATE_KEY_BURST) {
            BigDecimal source = jdbc.queryForObject("select balance from ledger_accounts where id = ?", BigDecimal.class,
                    LabLoadScope.DUP_SOURCE);
            if (source.compareTo(plan.amount()) < 0) {
                throw new LabValidationException("The duplicate-key source account holds " + source
                        + ", less than the amount; reset the sandbox first.");
            }
        }
        Run run = new Run(plan);
        switch (gate.tryStart(run.id.toString())) {
            case BUSY -> throw new LabBusyException("A load run is already in progress; only one runs at a time.");
            case COOLING_DOWN -> throw new LabBusyException("The previous run just finished; wait "
                    + props.load().cooldownSeconds() + " seconds between runs.");
            default -> {
            }
        }
        runs.put(run.id, run);
        activeRun = run.id;
        activeProfile = profileFor(plan);
        persist(run, "RUNNING");
        coordinator.submit(() -> execute(run));
        return view(run);
    }

    /** The fault profile for requests carrying this run id, or null. Used by the loopback fault filter. */
    LabFaultProfile faultProfileFor(String runId) {
        UUID active = activeRun;
        return active != null && active.toString().equals(runId) ? activeProfile : null;
    }

    public LabLoadRunView get(UUID id) {
        Run run = runs.get(id);
        if (run != null) {
            return view(run);
        }
        return stored(id);
    }

    public LabLoadRunView cancel(UUID id) {
        Run run = runs.get(id);
        if (run == null) {
            throw new LabNotFoundException("No active run " + id);
        }
        run.cancelled.set(true);
        return view(run);
    }

    public List<LabLoadRunView> history() {
        List<String> rows = jdbc.queryForList(
                "select result_json from lab_load_runs where result_json is not null order by started_at desc limit 10", String.class);
        List<LabLoadRunView> out = new ArrayList<>();
        for (String json : rows) {
            try {
                out.add(mapper.readValue(json, LabLoadRunView.class));
            } catch (JsonProcessingException e) {
                log.warn("Skipping unreadable stored load run", e);
            }
        }
        return out;
    }

    /** Subscribes to per-second samples; the Runnable fires once when the run completes. */
    public AutoCloseable subscribe(UUID id, Consumer<LabLoadSample> onSample, Runnable onComplete) {
        Run run = runs.get(id);
        if (run == null) {
            throw new LabNotFoundException("No run " + id + " is live");
        }
        run.listeners.add(onSample);
        run.completionListeners.add(onComplete);
        if (!"RUNNING".equals(run.status)) {
            onComplete.run();
        }
        return () -> {
            run.listeners.remove(onSample);
            run.completionListeners.remove(onComplete);
        };
    }

    @Override
    public void resetInMemory() {
        runs.values().removeIf(r -> !"RUNNING".equals(r.status));
    }

    // ------------------------------------------------------------------ execution

    private void execute(Run run) {
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lab-load-sampler");
            t.setDaemon(true);
            return t;
        });
        try {
            run.retriesBase = (long) meterRegistry.counter("settlement.finalize.retries").count();
            run.fallbackBase = (long) meterRegistry.counter("settlement.unknown.fallback").count();
            LabLoadInvariants.Baseline baseline = invariants.baseline();
            String token = personas.token("ADMIN").token();
            int port = Integer.parseInt(env.getProperty("local.server.port", env.getProperty("server.port", "8080")));
            LabLoadDriver driver = new LabLoadDriver(port, token, mapper);

            long[] previousTotal = {0};
            long[] previousNanos = {System.nanoTime()};
            int[] second = {0};
            sampler.scheduleAtFixedRate(() -> {
                try {
                    sample(run, previousTotal, previousNanos, ++second[0]);
                } catch (RuntimeException e) {
                    log.warn("Load sampler error", e);
                }
            }, 1, 1, TimeUnit.SECONDS);

            driver.run(run.plan, run.id, run.burstKey, activeProfile != null, run.stats, run.cancelled);

            sampler.shutdownNow();
            sample(run, previousTotal, previousNanos, ++second[0]);
            run.invariants = invariants.check(run.plan, baseline, run.burstKey);
            run.status = run.cancelled.get() ? "CANCELLED" : "COMPLETED";
        } catch (RuntimeException e) {
            log.error("Load run {} failed", run.id, e);
            run.status = "FAILED";
        } finally {
            sampler.shutdownNow();
            run.finishedAt = Instant.now();
            activeProfile = null;
            activeRun = null;
            gate.finish(run.id.toString());
            persist(run, run.status);
            run.completionListeners.forEach(Runnable::run);
        }
    }

    private void sample(Run run, long[] previousTotal, long[] previousNanos, int second) {
        long now = System.nanoTime();
        long total = run.stats.total();
        double rps = LabLoadStats.rate(previousTotal[0], total, (now - previousNanos[0]) / 1e9);
        previousTotal[0] = total;
        previousNanos[0] = now;
        LabLoadStats.Latency latency = run.stats.latency();
        HikariPoolMXBean pool = hikari();
        LabLoadSample sample = new LabLoadSample(second, Instant.now(), total, rps, latency.p50Ms(), latency.p95Ms(),
                latency.p99Ms(), run.stats.statusCounts(), run.stats.outcomeCounts(),
                (long) meterRegistry.counter("settlement.finalize.retries").count() - run.retriesBase,
                (long) meterRegistry.counter("settlement.unknown.fallback").count() - run.fallbackBase,
                pool == null ? 0 : pool.getActiveConnections(), pool == null ? 0 : pool.getIdleConnections(),
                pool == null ? 0 : pool.getThreadsAwaitingConnection());
        run.samples.add(sample);
        run.listeners.forEach(l -> l.accept(sample));
    }

    private HikariPoolMXBean hikari() {
        try {
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        } catch (Exception e) {
            return null;
        }
    }

    private LabFaultProfile profileFor(LoadPlan plan) {
        LoadPlan.FaultSpec f = plan.fault();
        if (f == null || f.total() == 0) {
            return null;
        }
        return new LabFaultProfile(Map.of(LabFault.REQUEST_LOST, f.requestLost(), LabFault.RESPONSE_LOST, f.responseLost(),
                LabFault.DECLINED, f.declined(), LabFault.SLOW, f.slow()), f.slowMs());
    }

    // ------------------------------------------------------------------ views and persistence

    private LabLoadRunView view(Run run) {
        List<LabLoadSample> samples = List.copyOf(run.samples);
        LabLoadStats.Latency latency = run.stats.latency();
        Instant end = run.finishedAt != null ? run.finishedAt : Instant.now();
        double seconds = Math.max(0.001, (end.toEpochMilli() - run.startedAt.toEpochMilli()) / 1000.0);
        long retries = samples.isEmpty() ? 0 : samples.get(samples.size() - 1).finalizeRetries();
        long fallbacks = samples.isEmpty() ? 0 : samples.get(samples.size() - 1).unknownFallbacks();
        LabLoadRunView.Summary summary = new LabLoadRunView.Summary(run.stats.total(), seconds,
                run.stats.total() / seconds, latency.p50Ms(), latency.p95Ms(), latency.p99Ms(), latency.maxMs(),
                run.stats.statusCounts(), run.stats.outcomeCounts(), retries, fallbacks,
                samples.stream().mapToInt(LabLoadSample::hikariActive).max().orElse(0),
                samples.stream().mapToInt(LabLoadSample::hikariPending).max().orElse(0));
        long unknown = run.stats.outcomeCounts().getOrDefault("UNKNOWN", 0L);
        List<LabInvariant> inv = run.invariants;
        String verdict = "RUNNING".equals(run.status) ? "PENDING"
                : inv.isEmpty() ? "NOT_EVALUATED"
                : inv.stream().anyMatch(i -> LabInvariant.FAIL.equals(i.status())) ? "FAIL" : "PASS";
        return new LabLoadRunView(run.id.toString(), run.status, run.plan, run.startedAt, run.finishedAt, samples, summary,
                inv, verdict, unknown > 0 ? new LabLoadRunView.Stranded(unknown, STRANDED_EXPLANATION) : null,
                k6Equivalent(run.plan),
                "Numbers measured by the lab runner (JVM client on loopback, same host as the app). They will differ from k6; "
                        + "quote the k6 numbers when comparing against a real deployment. See load/README.md.");
    }

    static String k6Equivalent(LoadPlan plan) {
        String scenario = switch (plan.scenario()) {
            case FRESH_SETTLEMENTS -> "fresh_settlements (constant-vus, exec freshSettlement)";
            case DUPLICATE_KEY_BURST -> "duplicate_key_burst (shared-iterations, exec duplicateKeySettlement)";
            case INVOICE_FINANCING -> "invoice_financing (shared-iterations, exec financeInvoice)";
        };
        return "k6 run load/settlement-load-test.js   # scenario " + scenario + "; the lab ran " + plan.virtualUsers()
                + " VUs for " + plan.durationSeconds() + "s, at most " + plan.totalRequests() + " requests, amount "
                + plan.amount() + ". Edit the scenario's vus/duration in the script's options to match.";
    }

    private void persist(Run run, String status) {
        try {
            String planJson = mapper.writeValueAsString(run.plan);
            String resultJson = "RUNNING".equals(status) ? null : mapper.writeValueAsString(view(run));
            int updated = jdbc.update("update lab_load_runs set status = ?, result_json = ?, finished_at = ? where id = ?",
                    status, resultJson, run.finishedAt == null ? null : java.sql.Timestamp.from(run.finishedAt), run.id);
            if (updated == 0) {
                jdbc.update("insert into lab_load_runs (id, scenario, plan_json, result_json, status, started_at, finished_at) "
                                + "values (?, ?, ?, ?, ?, ?, ?)", run.id, run.plan.scenario().name(), planJson, resultJson, status,
                        java.sql.Timestamp.from(run.startedAt), run.finishedAt == null ? null : java.sql.Timestamp.from(run.finishedAt));
            }
        } catch (Exception e) {
            // A reset truncates this table mid-write; the in-memory view still serves the run.
            log.warn("Could not persist load run {}: {}", run.id, e.getMessage());
        }
    }

    private LabLoadRunView stored(UUID id) {
        List<String> rows = jdbc.queryForList("select result_json from lab_load_runs where id = ? and result_json is not null",
                String.class, id);
        if (rows.isEmpty()) {
            throw new LabNotFoundException("No load run " + id);
        }
        try {
            return mapper.readValue(rows.get(0), LabLoadRunView.class);
        } catch (JsonProcessingException e) {
            throw new LabNotFoundException("Load run " + id + " is unreadable");
        }
    }
}
