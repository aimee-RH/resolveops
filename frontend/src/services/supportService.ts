import { api } from "@/services/api";
import type { SupportCase, SupportEvidence, SupportRun, SupportRunEvent } from "@/types/support";

const ref = encodeURIComponent;

export const supportService = {
  cases: () => api.get<SupportCase[], SupportCase[]>("/support/cases"),
  detail: (id: string) => api.get<SupportCase, SupportCase>(`/support/cases/${ref(id)}`),
  runs: (id: string) => api.get<SupportRun[], SupportRun[]>(`/support/cases/${ref(id)}/runs`),
  evidence: (id: string) => api.get<SupportEvidence[], SupportEvidence[]>(`/support/runs/${ref(id)}/evidence`),
  events: (id: string) => api.get<SupportRunEvent[], SupportRunEvent[]>(`/support/runs/${ref(id)}/events`)
};
