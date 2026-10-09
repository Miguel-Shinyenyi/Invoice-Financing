package com.settlementengine.core.lab;

import com.settlementengine.core.domain.Role;
import com.settlementengine.core.security.JwtService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Issues real JWTs for the three sandbox users so the UI exercises the real, protected endpoints. */
@LabComponent
public class LabPersonaService {

    public record Persona(String role, String username, UUID userId, UUID ownerId, List<UUID> ownedAccounts,
                          String sandboxPassword) {
    }

    public record PersonaToken(Persona persona, String token, long expiresInSeconds) {
    }

    private final JdbcTemplate jdbc;
    private final JwtService jwtService;
    private final long ttlSeconds;

    public LabPersonaService(JdbcTemplate jdbc, JwtService jwtService,
                             @org.springframework.beans.factory.annotation.Value("${settlement-engine.jwt.access-token-ttl-seconds:900}") long ttlSeconds) {
        this.jdbc = jdbc;
        this.jwtService = jwtService;
        this.ttlSeconds = ttlSeconds;
    }

    public List<Persona> personas() {
        return List.of(find(Role.ADMIN), find(Role.SUPPORT), find(Role.READ_ONLY));
    }

    public PersonaToken token(String roleName) {
        Role role;
        try {
            role = Role.valueOf(roleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new LabValidationException("role must be one of ADMIN, SUPPORT, READ_ONLY");
        }
        Persona persona = find(role);
        String token = jwtService.issueAccessToken(persona.userId(), role.name(), persona.ownerId());
        return new PersonaToken(persona, token, ttlSeconds);
    }

    private Persona find(Role role) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, username, owner_id from users where role = ? order by username limit 1", role.name());
        if (rows.isEmpty()) {
            throw new LabNotFoundException("No sandbox user for role " + role);
        }
        Map<String, Object> row = rows.get(0);
        UUID ownerId = (UUID) row.get("owner_id");
        List<UUID> owned = ownerId == null ? List.of()
                : jdbc.queryForList("select id from ledger_accounts where owner_id = ? order by id", UUID.class, ownerId);
        String username = (String) row.get("username");
        return new Persona(role.name(), username, (UUID) row.get("id"), ownerId, owned, username + "-sandbox");
    }
}
