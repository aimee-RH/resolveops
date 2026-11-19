import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { RefreshCw, Search } from "lucide-react";

import { Button } from "@/components/ui/button";
import { AgentLayout } from "@/components/agent/AgentLayout";
import { SupportRepairPanel } from "@/components/SupportRepairPanel";
import { supportService } from "@/services/supportService";
import type { SupportCase, SupportEvidence, SupportRun, SupportRunEvent } from "@/types/support";
import "@/styles/support-workbench.css";

const scenarioName = { SUBSCRIPTION_ACTIVATION: "Subscription activation", API_AUTHORIZATION: "API authorization" };
const message = (error: unknown) => error instanceof Error ? error.message : "Unable to load. Please try again.";
const outcomeName = { PROPOSE_REPAIR: "Repair recommended", NO_ACTION_NEEDED: "No action needed", NEEDS_INFORMATION: "More information needed", HANDOFF: "Human review needed" };
const ongoing = (run: SupportRun) => run.status === "PENDING" || run.status === "RUNNING";
const evidenceName: Record<string, string> = { CASE: "Customer report", SUBSCRIPTION: "Subscription status", EVENTS: "Subscription events", RUNBOOK: "Runbook", INTEGRATION: "Integration settings", API_DENIALS: "API request logs" };
const eventName: Record<string, string> = { INVESTIGATION_STARTED: "Investigation started", TOOL_COMPLETED: "Evidence collected", DIAGNOSIS_STARTED: "Preparing diagnosis", INVESTIGATION_COMPLETED: "Investigation completed" };

function revealEvidence(id: string) {
  const panel = document.getElementById(`evidence-${id}`);
  if (panel instanceof HTMLDetailsElement) panel.open = true;
}

export function SupportCasePage() {
  const { caseId } = useParams();
  const navigate = useNavigate();
  const [cases, setCases] = useState<SupportCase[]>([]);
  const [detail, setDetail] = useState<SupportCase | null>(null);
  const [runs, setRuns] = useState<SupportRun[]>([]);
  const [runId, setRunId] = useState<string | null>(null);
  const [evidence, setEvidence] = useState<SupportEvidence[]>([]);
  const [events, setEvents] = useState<SupportRunEvent[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refresh, setRefresh] = useState(0);
  const [enabled, setEnabled] = useState(false);
  const [repairEnabled, setRepairEnabled] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const currentCase = useRef(caseId);
  currentCase.current = caseId;
  const request = useRef<{ caseId: string; key: string } | null>(null);

  useEffect(() => {
    let current = true;
    Promise.all([supportService.cases(), supportService.capabilities()]).then(([result, capabilities]) => {
      if (!current) return;
      setCases(result);
      setEnabled(capabilities.investigationEnabled);
      setRepairEnabled(capabilities.repairEnabled);
      if (!caseId && result[0]) navigate(`/support/${encodeURIComponent(result[0].id)}`, { replace: true });
      if (!caseId && !result.length) setLoading(false);
    }).catch((cause: unknown) => { if (current) { setError(message(cause)); setLoading(false); } });
    return () => { current = false; };
  }, [caseId, navigate, refresh]);

  useEffect(() => {
    setDetail(null);
    setRuns([]);
    setRunId(null);
    setEvidence([]);
    setEvents([]);
    setError(null);
    if (!caseId) return;
    let current = true;
    setLoading(true);
    Promise.all([supportService.detail(caseId), supportService.runs(caseId)])
      .then(([record, history]) => {
        if (!current) return;
        setDetail(record);
        setRuns(history);
        setRunId(history[0]?.id ?? null);
      }).catch((cause: unknown) => { if (current) setError(message(cause)); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [caseId, refresh]);

  useEffect(() => {
    setEvidence([]);
    setEvents([]);
    if (!runId) return;
    let current = true;
    let timer: ReturnType<typeof setTimeout>;
    const poll = () => {
      Promise.all([supportService.run(runId), supportService.evidence(runId), supportService.events(runId)])
        .then(([run, facts, history]) => {
          if (!current) return;
          setRuns((values) => values.map((value) => value.id === run.id ? (!ongoing(value) && ongoing(run) ? value : run) : value));
          setEvidence(facts);
          setEvents(history);
          if (ongoing(run)) timer = setTimeout(poll, 2000);
        }).catch((cause: unknown) => { if (current) setError(message(cause)); });
    };
    poll();
    return () => { current = false; clearTimeout(timer); };
  }, [runId, refresh]);

  const selectedRun = runs.find((run) => run.id === runId);
  const investigate = async () => {
    if (!caseId || submitting) return;
    const expectedCase = caseId;
    if (request.current?.caseId !== caseId) request.current = { caseId, key: crypto.randomUUID() };
    setSubmitting(true);
    setError(null);
    try {
      const run = await supportService.investigate(caseId, request.current.key);
      if (currentCase.current !== expectedCase) return;
      setRuns((values) => [run, ...values.filter((value) => value.id !== run.id)]);
      setRunId(run.id);
      request.current = null;
    } catch (cause) { if (currentCase.current === expectedCase) setError(message(cause)); }
    finally { setSubmitting(false); }
  };
  const cancel = async () => {
    if (!selectedRun || submitting) return;
    const expectedCase = caseId;
    setSubmitting(true);
    try {
      const run = await supportService.cancel(selectedRun.id);
      if (currentCase.current === expectedCase)
        setRuns((values) => values.map((value) => value.id === run.id ? run : value));
    } catch (cause) { if (currentCase.current === expectedCase) setError(message(cause)); }
    finally { setSubmitting(false); }
  };

  return (
    <AgentLayout supportWorkbench sidebar={
      <nav aria-label="Case list" className="support-case-list">
        <h2>Your cases</h2>
        {!loading && !cases.length && <p>No accessible cases. Check your customer access grants.</p>}
        <div className="support-case-links">
          {cases.map((record) => <Link key={record.id} to={`/support/${encodeURIComponent(record.id)}`} aria-current={record.id === caseId ? "page" : undefined}>
            <span>{scenarioName[record.scenario]}</span><small>{record.id}</small>
          </Link>)}
        </div>
      </nav>
    }>
      <div className="support-page">
        <header className="support-toolbar">
          <div><h1>Case investigation</h1><p>Review the customer report, diagnosis and supporting evidence.</p></div>
          <Button variant="outline" size="sm" onClick={() => setRefresh((value) => value + 1)}><RefreshCw className="h-3.5 w-3.5" />Refresh</Button>
        </header>
        {error && <p role="alert" className="support-error">{error}</p>}
        <div className="support-columns">
          <section aria-label="Case details" className="support-case-detail">
            {loading && <p role="status">Loading case…</p>}
            {detail && <>
              <section className="support-card">
                <h2>{scenarioName[detail.scenario]}</h2>
                <p className="support-label">Original customer report</p>
                <p className="whitespace-pre-wrap">{detail.description}</p>
                <dl className="support-case-meta"><dt>Customer</dt><dd>{detail.tenantId}</dd><dt>Resource</dt><dd>{detail.resourceId}</dd><dt>Case status</dt><dd>{detail.status}</dd></dl>
                <Button disabled={!enabled || submitting || runs.some(ongoing)} onClick={investigate}><Search className="h-4 w-4" />{submitting ? "Submitting…" : "Start investigation"}</Button>
                <p className="support-hint">{enabled ? "Reads current records and runbooks. Every conclusion links to its evidence." : "Investigation is disabled. You can review saved results."}</p>
              </section>
              <section className="support-card" aria-label="Investigation history">
                <h2>Investigation history</h2>
                {!runs.length && <p className="support-hint">Start an investigation to see its progress and results here.</p>}
                {runs.length > 0 && <select aria-label="Select investigation" value={runId ?? ""} onChange={(event) => setRunId(event.target.value)}>
                  {runs.map((run) => <option key={run.id} value={run.id}>{new Date(run.startedAt).toLocaleString("en-US")} · {run.status}</option>)}
                </select>}
                {selectedRun && <div className="support-diagnosis">
                  <p className="support-label">{ongoing(selectedRun) ? "Investigation in progress" : `Run ${selectedRun.status.toLowerCase()}`}</p>
                  {ongoing(selectedRun) && <Button variant="outline" size="sm" disabled={submitting} onClick={cancel}>Stop investigation</Button>}
                  {selectedRun.diagnosis?.outcome && <h3>{outcomeName[selectedRun.diagnosis.outcome]}</h3>}
                  {selectedRun.diagnosis?.recommendedAction?.type === "RECONCILE_ACTIVATION" && <p>Reconcile the recorded payment with the subscription activation state.</p>}
                  {selectedRun.diagnosis?.error && <p role="alert" className="support-error">Investigation incomplete ({selectedRun.diagnosis.error}). Review the collected evidence before starting again.</p>}
                  {selectedRun.diagnosis?.facts?.map((fact, index) => <div key={index} className="support-fact">
                    <p>{fact.text}</p>
                    <div className="support-source-links">{fact.evidenceIds.map((id) => {
                      const source = evidence.find((item) => item.id === id);
                      return <a key={id} href={`#evidence-${id}`} onClick={() => revealEvidence(id)}>View {evidenceName[source?.kind ?? ""] ?? "source"}</a>;
                    })}</div>
                  </div>)}
                  {!!selectedRun.diagnosis?.runbookEvidenceIds?.length && <ul aria-label="Cited runbooks" className="support-source-links">
                    {selectedRun.diagnosis.runbookEvidenceIds.map((id) => {
                      const title = evidence.find((item) => item.id === id)?.snapshot.title;
                      return <li key={id}><a href={`#evidence-${id}`} onClick={() => revealEvidence(id)}>{typeof title === "string" ? title : "View runbook"}</a></li>;
                    })}
                  </ul>}
                  {selectedRun.diagnosis?.hypotheses?.map((item, index) => <p key={index} className="support-next-step"><strong>To verify</strong> {item}</p>)}
                  {selectedRun.diagnosis?.missingInformation?.map((item, index) => <p key={index} className="support-next-step"><strong>Missing information</strong> {item}</p>)}
                  {repairEnabled && detail.scenario === "SUBSCRIPTION_ACTIVATION" && selectedRun.status === "COMPLETED" && selectedRun.diagnosis?.outcome === "PROPOSE_REPAIR" && <>
                    <p className="support-hint">A separate reviewer must approve the proposal before the requester can execute it.</p>
                    <SupportRepairPanel key={selectedRun.id} runId={selectedRun.id} />
                  </>}
                </div>}
              </section>
              {events.length > 0 && <details className="support-card support-trace"><summary>Execution trace · {events.length} steps</summary>
                <ol>{events.map((event) => <li key={event.sequence}><p>{eventName[event.type] ?? event.type}</p><pre>{JSON.stringify(event.payload, null, 2)}</pre></li>)}</ol>
              </details>}
            </>}
          </section>
          <aside aria-label="Run evidence" className="support-evidence">
            <h2>Run evidence</h2><p className="support-hint">Open a source to inspect the records used in the selected investigation.</p>
            {!evidence.length && <p className="support-hint">No evidence collected yet.</p>}
            {evidence.map((item) => <details key={item.id} id={`evidence-${item.id}`} className="support-card support-source">
              <summary>{evidenceName[item.kind] ?? item.kind}<small>{item.sourceRef}</small></summary>
              <p className="support-hint">Observed {new Date(item.observedAt).toLocaleString("en-US")}</p>
              <pre>{JSON.stringify(item.snapshot, null, 2)}</pre>
              <p className="support-hash">Content hash · {item.hash}</p>
            </details>)}
          </aside>
        </div>
      </div>
    </AgentLayout>
  );
}
