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
  diagnosis: Record<string, unknown>;
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
