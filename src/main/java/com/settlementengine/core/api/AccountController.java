package com.settlementengine.core.api;

import com.settlementengine.core.domain.AccountNotFoundException;
import com.settlementengine.core.domain.AuditOutcome;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.security.AccessTokenClaims;
import com.settlementengine.core.security.AuditLogService;
import com.settlementengine.core.security.RowLevelAccessGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@Tag(name = "Accounts", description = "Read-only ledger account lookups")
public class AccountController {

    private final LedgerAccountRepository ledgerAccountRepository;
    private final RowLevelAccessGuard rowLevelAccessGuard;
    private final AuditLogService auditLogService;

    public AccountController(LedgerAccountRepository ledgerAccountRepository, RowLevelAccessGuard rowLevelAccessGuard,
                              AuditLogService auditLogService) {
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.rowLevelAccessGuard = rowLevelAccessGuard;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a ledger account by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "READ_ONLY user does not own this account", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No account with this id", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public AccountResponse get(@PathVariable UUID id, @AuthenticationPrincipal AccessTokenClaims claims) {
        LedgerAccount account = ledgerAccountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        try {
            rowLevelAccessGuard.requireOwnership(claims, account.getOwnerId());
        } catch (AccessDeniedException denied) {
            auditLogService.record(claims.userId(), "GET_ACCOUNT", "ledger_accounts", id, AuditOutcome.DENIED);
            throw denied;
        }
        auditLogService.record(claims.userId(), "GET_ACCOUNT", "ledger_accounts", id, AuditOutcome.SUCCESS);
        return AccountResponse.from(account);
    }
}
