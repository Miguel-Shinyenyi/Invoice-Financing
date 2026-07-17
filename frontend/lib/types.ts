// Mirrors Spring Data's PagedModel (VIA_DTO serialization -- see SettlementEngineApplication.java).
export interface Page<T> {
  content: T[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}

export interface SettlementSummary {
  settlementId: string;
  sourceAccountId: string;
  destinationAccountId: string;
  amount: number;
  currency: string;
  status: string;
  updatedAt: string;
}

export interface Settlement {
  settlementId: string;
  sourceAccountId: string;
  destinationAccountId: string;
  amount: number;
  currency: string;
  status: string;
  externalRef: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Account {
  id: string;
  ownerId: string;
  balance: number;
  currency: string;
  createdAt: string;
}

export interface InvoiceSummary {
  id: string;
  businessAccountId: string;
  customerReference: string;
  amount: number;
  currency: string;
  dueDate: string;
  status: string;
  externalSourceRef: string;
  updatedAt: string;
}

export interface Advance {
  id: string;
  amountAdvanced: number;
  fee: number;
  disbursedSettlementId: string;
  repaidSettlementId: string | null;
  status: string;
}

export interface Invoice {
  id: string;
  businessAccountId: string;
  customerReference: string;
  amount: number;
  currency: string;
  dueDate: string;
  status: string;
  externalSourceRef: string;
  createdAt: string;
  updatedAt: string;
  advance: Advance | null;
}

export interface ReconciliationMismatch {
  id: string;
  runId: string;
  settlementId: string;
  internalState: string;
  externalState: string | null;
  details: string;
  resolutionStatus: string;
  resolvedAt: string | null;
  createdAt: string;
}
