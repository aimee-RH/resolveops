import { api } from "@/services/api";
import type { SupportCase, SupportEvidence, SupportRun, SupportRunEvent } from "@/types/support";

const ref = encodeURIComponent;

export const supportService = {
  capabilities: () => api.get<{ investigationEnabled: boolean }, { investigationEnabled: boolean }>("/support/capabilities"),
  cases: () => api.get<SupportCase[], SupportCase[]>("/support/cases"),
  detail: (id: string) => api.get<SupportCase, SupportCase>(`/support/cases/${ref(id)}`),
  runs: (id: string) => api.get<SupportRun[], SupportRun[]>(`/support/cases/${ref(id)}/runs`),
  run: (id: string) => api.get<SupportRun, SupportRun>(`/support/runs/${ref(id)}`),
  investigate: (id: string, requestKey: string) => api.post<SupportRun, SupportRun>(`/support/cases/${ref(id)}/runs`, { requestKey }),
  cancel: (id: string) => api.post<SupportRun, SupportRun>(`/support/runs/${ref(id)}/cancel`),
  evidence: (id: string) => api.get<SupportEvidence[], SupportEvidence[]>(`/support/runs/${ref(id)}/evidence`),
  events: (id: string) => api.get<SupportRunEvent[], SupportRunEvent[]>(`/support/runs/${ref(id)}/events`)
};
