package com.settlementengine.core.api;

import com.settlementengine.core.domain.AccountNotFoundException;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.repository.LedgerAccountRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    public AccountController(LedgerAccountRepository ledgerAccountRepository) {
        this.ledgerAccountRepository = ledgerAccountRepository;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a ledger account by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account found"),
            @ApiResponse(responseCode = "404", description = "No account with this id", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public AccountResponse get(@PathVariable UUID id) {
        LedgerAccount account = ledgerAccountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        return AccountResponse.from(account);
    }
}
