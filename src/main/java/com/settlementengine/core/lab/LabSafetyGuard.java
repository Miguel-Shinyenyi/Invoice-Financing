package com.settlementengine.core.lab;

import java.net.URI;
import java.util.List;

/**
 * Refuses to start the lab against anything that is not obviously a sandbox database. The lab can
 * truncate tables ({@code LabResetService}) and run load, so pointing it at the shared staging
 * database by mistake must be impossible, not merely unlikely.
 */
public final class LabSafetyGuard {

    private LabSafetyGuard() {
    }

    public static void verify(String jdbcUrl, List<String> forbiddenHosts) {
        URI uri = parse(jdbcUrl);
        String host = uri.getHost();
        if (host != null && forbiddenHosts.contains(host)) {
            throw new IllegalStateException("Lab refuses to start: datasource host " + host
                    + " is listed in settlement-engine.demo.forbidden-hosts (the shared staging server)");
        }
        String database = databaseName(jdbcUrl);
        if (!database.endsWith("_lab")) {
            throw new IllegalStateException("Lab refuses to start: datasource database '" + database
                    + "' does not end in _lab");
        }
    }

    public static String databaseName(String jdbcUrl) {
        String path = parse(jdbcUrl).getPath();
        if (path == null || path.length() < 2) {
            throw new IllegalStateException("Lab refuses to start: datasource URL names no database");
        }
        return path.substring(1);
    }

    private static URI parse(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            throw new IllegalStateException("Lab refuses to start: datasource URL is not a JDBC URL");
        }
        try {
            return URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Lab refuses to start: datasource URL is unparseable");
        }
    }
}
