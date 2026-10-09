package com.settlementengine.core.lab;

import java.util.List;
import java.util.UUID;

/** The fixed account ids the load scenarios use (same ids as load/seed-accounts.sql). */
public final class LabLoadScope {

    public static final List<UUID> POOL = List.of(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            UUID.fromString("10000000-0000-0000-0000-000000000002"),
            UUID.fromString("10000000-0000-0000-0000-000000000003"),
            UUID.fromString("10000000-0000-0000-0000-000000000004"),
            UUID.fromString("10000000-0000-0000-0000-000000000005"),
            UUID.fromString("10000000-0000-0000-0000-000000000006"));
    public static final UUID DUP_SOURCE = UUID.fromString("20000000-0000-0000-0000-000000000001");
    public static final UUID DUP_DESTINATION = UUID.fromString("20000000-0000-0000-0000-000000000002");
    public static final UUID BUSINESS = UUID.fromString("30000000-0000-0000-0000-000000000001");
    public static final UUID PLATFORM = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private LabLoadScope() {
    }
}
