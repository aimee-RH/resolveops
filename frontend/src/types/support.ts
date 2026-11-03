export interface SupportCase {
  id: string;
  tenantId: string;
  scenario: "SUBSCRIPTION_ACTIVATION" | "API_AUTHORIZATION";
  resourceId: string;
  description: string;
  status: string;
  version: number;
}

export interface SupportRun {
  id: string;
  caseId: string;
  actorId: string;
  status: string;
  diagnosis: {
    outcome?: "PROPOSE_REPAIR" | "NO_ACTION_NEEDED" | "NEEDS_INFORMATION" | "HANDOFF";
    facts?: { text: string; evidenceIds: string[] }[];
    hypotheses?: string[];
    missingInformation?: string[];
    recommendedAction?: { type: string } | null;
    runbookEvidenceIds?: string[];
    error?: string;
  };
  config: Record<string, unknown>;
  startedAt: string;
  finishedAt: string | null;
}

export interface SupportEvidence {
  id: string;
  kind: string;
  sourceRef: string;
  version: string | null;
  hash: string;
  observedAt: string;
  snapshot: Record<string, unknown>;
}

export interface SupportRunEvent {
  sequence: number;
  type: string;
  payload: Record<string, unknown>;
}
