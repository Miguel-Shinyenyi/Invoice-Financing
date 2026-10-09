package com.settlementengine.core.reconciliation;

import java.util.Optional;

public interface ExternalReconciliationSource {

    Optional<ExternalRecord> findByReference(String reference);
}
