package com.settlementengine.core.security;

import com.settlementengine.core.domain.AuditLogEntry;
import com.settlementengine.core.domain.AuditOutcome;
import com.settlementengine.core.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void record(UUID actorId, String action, String targetTable, UUID targetId, AuditOutcome outcome) {
        auditLogRepository.save(new AuditLogEntry(UUID.randomUUID(), actorId, action, targetTable, targetId, outcome));
    }
}
