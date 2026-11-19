import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { SupportRepairPanel } from "@/components/SupportRepairPanel";
import { supportService } from "@/services/supportService";
import type { SupportCase, SupportEvidence, SupportRun, SupportRunEvent } from "@/types/support";

const scenarioName = { SUBSCRIPTION_ACTIVATION: "Subscription activation", API_AUTHORIZATION: "API authorization" };
const message = (error: unknown) => error instanceof Error ? error.message : "Unable to load. Please try again.";
const outcomeName = { PROPOSE_REPAIR: "Repair recommended", NO_ACTION_NEEDED: "No action needed", NEEDS_INFORMATION: "More information needed", HANDOFF: "Human review needed" };
const ongoing = (run: SupportRun) => run.status === "PENDING" || run.status === "RUNNING";

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
    <main lang="en" aria-label="Support workbench" tabIndex={0} className="h-dvh overflow-y-auto bg-background text-foreground">
      <header className="flex flex-wrap items-center justify-between gap-4 border-b px-6 py-5">
        <div><h1 className="text-xl font-semibold">ResolveOps Support Workbench</h1><p className="text-sm text-muted-foreground">Investigate customer incidents and review the supporting evidence.</p></div>
        <div className="flex items-center gap-4"><Button variant="outline" onClick={() => setRefresh((value) => value + 1)}>Refresh</Button><Link className="text-sm underline" to="/chat">Back to chat</Link></div>
      </header>
      <p className="border-b bg-muted/40 px-6 py-3 text-sm">
        {enabled ? "Investigations read business records and runbooks to produce diagnoses linked to evidence." : "View existing cases, investigation history and evidence."}
        {repairEnabled && " A separate authorized reviewer must approve a repair before the requester can execute it."}
      </p>
      {error && <p role="alert" className="m-6 rounded border border-destructive p-4 text-destructive">{error}</p>}
      <div className="grid gap-6 p-6 lg:grid-cols-[260px_minmax(0,1fr)_340px]">
        <nav aria-label="Case list" className="space-y-3">
          <h2 className="font-semibold">Your cases</h2>
          {!loading && !cases.length && <p className="text-sm text-muted-foreground">No accessible cases. Check that case records and customer access grants are configured.</p>}
          {cases.map((record) => <Link key={record.id} to={`/support/${encodeURIComponent(record.id)}`} aria-current={record.id === caseId ? "page" : undefined}
            className={`block rounded-lg border p-3 text-sm ${record.id === caseId ? "border-primary bg-muted" : "hover:bg-muted/50"}`}>
            <span className="block font-medium">{scenarioName[record.scenario]}</span><span className="block break-all text-muted-foreground">{record.id}</span>
          </Link>)}
        </nav>
        <section aria-label="Case details" className="min-w-0 space-y-5">
          {loading && <p role="status">Loading case…</p>}
          {detail && <>
            <div className="rounded-lg border p-5"><h2 className="text-lg font-semibold">{scenarioName[detail.scenario]}</h2>
              <p className="mt-3 whitespace-pre-wrap">{detail.description}</p>
              <dl className="mt-4 grid grid-cols-2 gap-2 text-sm"><dt>Customer tenant</dt><dd className="break-all">{detail.tenantId}</dd><dt>Resource</dt><dd className="break-all">{detail.resourceId}</dd><dt>Case status</dt><dd>{detail.status}</dd></dl>
              <Button className="mt-4" disabled={!enabled || submitting || runs.some(ongoing)} onClick={investigate}>
                {submitting ? "Submitting…" : "Start investigation"}
              </Button>
            </div>
            <div className="space-y-3"><h2 className="font-semibold">Investigation history</h2>
              {!runs.length && <p className="text-sm text-muted-foreground">No investigations yet. Start one to see its progress and results here.</p>}
              {runs.length > 0 && <select aria-label="Select investigation" value={runId ?? ""} onChange={(event) => setRunId(event.target.value)} className="w-full rounded border bg-background p-2 text-sm">
                {runs.map((run) => <option key={run.id} value={run.id}>{run.status} · {run.startedAt}</option>)}
              </select>}
              {selectedRun && <div className="rounded-lg border p-4"><p className="text-sm">Run status · {selectedRun.status}</p>
                {ongoing(selectedRun) && <Button variant="outline" className="mt-3" disabled={submitting} onClick={cancel}>Stop investigation</Button>}
                {selectedRun.diagnosis?.outcome && <h3 className="mt-3 font-medium">{outcomeName[selectedRun.diagnosis.outcome]}</h3>}
                {selectedRun.diagnosis?.recommendedAction?.type === "RECONCILE_ACTIVATION" &&
                  <p className="mt-2 text-sm">Reconcile the recorded payment with the subscription activation state.</p>}
                {!!selectedRun.diagnosis?.runbookEvidenceIds?.length && <ul aria-label="Cited runbooks" className="mt-2 space-y-1 text-sm">
                  {selectedRun.diagnosis.runbookEvidenceIds.map((id) => {
                    const title = evidence.find((item) => item.id === id)?.snapshot.title;
                    return <li key={id}><a className="break-all underline" href={`#evidence-${id}`} onClick={() => {
                      const panel = document.getElementById(`evidence-${id}`);
                      if (panel instanceof HTMLDetailsElement) panel.open = true;
                    }}>{typeof title === "string" ? title : id}</a></li>;
                  })}
                </ul>}
                {selectedRun.diagnosis?.error && <p role="alert" className="mt-3 text-sm">Investigation incomplete ({selectedRun.diagnosis.error}). Review the collected evidence before starting again.</p>}
                {selectedRun.diagnosis?.facts?.map((fact, index) => <div key={index} className="mt-3 text-sm"><p>{fact.text}</p>
                  <p className="break-all text-xs text-muted-foreground">Evidence · {fact.evidenceIds.join(", ")}</p></div>)}
                {selectedRun.diagnosis?.hypotheses?.map((item, index) => <p key={index} className="mt-2 text-sm">To verify · {item}</p>)}
                {selectedRun.diagnosis?.missingInformation?.map((item, index) => <p key={index} className="mt-2 text-sm">Missing information · {item}</p>)}
                {repairEnabled && detail.scenario === "SUBSCRIPTION_ACTIVATION" && selectedRun.status === "COMPLETED" && selectedRun.diagnosis?.outcome === "PROPOSE_REPAIR" &&
                  <SupportRepairPanel key={selectedRun.id} runId={selectedRun.id} />}
              </div>}
              {events.length > 0 && <ol className="space-y-2 text-sm">{events.map((event) => <li key={event.sequence} className="rounded border p-3">{event.type}<pre className="mt-2 whitespace-pre-wrap break-words text-xs">{JSON.stringify(event.payload, null, 2)}</pre></li>)}</ol>}
            </div>
          </>}
        </section>
        <aside aria-label="Run evidence" className="min-w-0 space-y-3"><h2 className="font-semibold">Run evidence</h2>
          {!evidence.length && <p className="text-sm text-muted-foreground">Select an investigation to view the evidence it collected.</p>}
          {evidence.map((item) => <details key={item.id} id={`evidence-${item.id}`} className="rounded-lg border p-3 text-sm"><summary className="cursor-pointer break-all">{item.kind} · {item.sourceRef}</summary>
            <p className="mt-2 break-all text-xs text-muted-foreground">Observed at · {item.observedAt}<br />Content hash · {item.hash}</p>
            <pre className="mt-3 whitespace-pre-wrap break-words text-xs">{JSON.stringify(item.snapshot, null, 2)}</pre>
          </details>)}
        </aside>
      </div>
    </main>
  );
}
