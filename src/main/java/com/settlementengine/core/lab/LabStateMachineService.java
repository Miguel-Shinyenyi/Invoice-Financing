package com.settlementengine.core.lab;

import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.invoicing.AdvanceStatus;
import com.settlementengine.core.invoicing.InvoiceStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/** State machines read from the enums' own {@code canTransitionTo} rules (never hand-copied), with live counts. */
@LabComponent
public class LabStateMachineService {

    private static final Map<String, String> NO_CODE_PATH = Map.of(
            "SETTLEMENT.REVERSED", "Reachable from CONFIRMED in the state machine, but no code in src/main ever transitions to it. "
                    + "Known gap 3. The one REVERSED settlement in the seed was inserted by hand.",
            "ADVANCE.DEFAULTED", "Allowed by AdvanceStatus.canTransitionTo, but nothing in src/main ever sets it: an OVERDUE invoice's "
                    + "advance stays DISBURSED.");

    private final JdbcTemplate jdbc;

    public LabStateMachineService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settlement", machine("SETTLEMENT", SettlementStatus.values(), SettlementStatus::canTransitionTo,
                counts("select status, count(*) from settlements group by status")));
        out.put("invoice", machine("INVOICE", InvoiceStatus.values(), InvoiceStatus::canTransitionTo,
                counts("select status, count(*) from invoices group by status")));
        out.put("advance", machine("ADVANCE", AdvanceStatus.values(), AdvanceStatus::canTransitionTo,
                counts("select status, count(*) from advances group by status")));

        Map<String, Object> rm = new LinkedHashMap<>();
        Map<String, Long> write = zeroed(SettlementStatus.values());
        write.putAll(counts("select status, count(*) from settlements group by status"));
        Map<String, Long> read = zeroed(SettlementStatus.values());
        read.putAll(counts("select status, count(*) from settlement_read_model group by status"));
        rm.put("writeSide", write);
        rm.put("readSide", read);
        out.put("readModel", rm);
        return out;
    }

    private <E extends Enum<E>> Map<String, Object> machine(String key, E[] values, BiPredicate<E, E> canTransition,
                                                             Map<String, Long> counts) {
        List<Map<String, Object>> states = new ArrayList<>();
        List<Map<String, String>> transitions = new ArrayList<>();
        for (E from : values) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", from.name());
            s.put("count", counts.getOrDefault(from.name(), 0L));
            String note = NO_CODE_PATH.get(key + "." + from.name());
            s.put("noCodePath", note != null);
            s.put("note", note);
            boolean terminal = true;
            for (E to : values) {
                if (canTransition.test(from, to)) {
                    terminal = false;
                    transitions.add(Map.of("from", from.name(), "to", to.name()));
                }
            }
            s.put("terminal", terminal);
            states.add(s);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("states", states);
        m.put("transitions", transitions);
        return m;
    }

    private Map<String, Long> counts(String sql) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        });
        return out;
    }

    private static <E extends Enum<E>> Map<String, Long> zeroed(E[] values) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (E v : values) {
            m.put(v.name(), 0L);
        }
        return m;
    }
}
