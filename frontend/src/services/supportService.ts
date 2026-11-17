import { api } from "@/services/api";
import type { SupportCase, SupportEvidence, SupportRepair, SupportRun, SupportRunEvent } from "@/types/support";

const ref = encodeURIComponent;
type SupportCapabilities = { investigationEnabled: boolean; repairEnabled: boolean };
const repairPath = (id: string) => `/support/runs/${ref(id)}/repair`;

export const supportService = {
  capabilities: () => api.get<SupportCapabilities, SupportCapabilities>("/support/capabilities"),
  cases: () => api.get<SupportCase[], SupportCase[]>("/support/cases"),
  detail: (id: string) => api.get<SupportCase, SupportCase>(`/support/cases/${ref(id)}`),
  runs: (id: string) => api.get<SupportRun[], SupportRun[]>(`/support/cases/${ref(id)}/runs`),
  run: (id: string) => api.get<SupportRun, SupportRun>(`/support/runs/${ref(id)}`),
  investigate: (id: string, requestKey: string) => api.post<SupportRun, SupportRun>(`/support/cases/${ref(id)}/runs`, { requestKey }),
  cancel: (id: string) => api.post<SupportRun, SupportRun>(`/support/runs/${ref(id)}/cancel`),
  evidence: (id: string) => api.get<SupportEvidence[], SupportEvidence[]>(`/support/runs/${ref(id)}/evidence`),
  events: (id: string) => api.get<SupportRunEvent[], SupportRunEvent[]>(`/support/runs/${ref(id)}/events`),
  repair: (id: string) => api.get<SupportRepair, SupportRepair>(repairPath(id)),
  proposeRepair: (id: string) => api.post<SupportRepair, SupportRepair>(repairPath(id)),
  decideRepair: (id: string, action: "approve" | "reject" | "execute", payloadHash: string) =>
    api.post<SupportRepair, SupportRepair>(`${repairPath(id)}/${action}`, { payloadHash })
};
