import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { supportService } from "@/services/supportService";
import type { SupportCase, SupportEvidence, SupportRun, SupportRunEvent } from "@/types/support";

const scenarioName = { SUBSCRIPTION_ACTIVATION: "订阅开通故障", API_AUTHORIZATION: "API 授权故障" };
const message = (error: unknown) => error instanceof Error ? error.message : "读取失败，请稍后重试";

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

  useEffect(() => {
    let current = true;
    supportService.cases().then((result) => {
      if (!current) return;
      setCases(result);
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
    Promise.all([supportService.evidence(runId), supportService.events(runId)])
      .then(([facts, history]) => { if (current) { setEvidence(facts); setEvents(history); } })
      .catch((cause: unknown) => { if (current) setError(message(cause)); });
    return () => { current = false; };
  }, [runId, refresh]);

  const selectedRun = runs.find((run) => run.id === runId);

  return (
    <main className="min-h-screen bg-background text-foreground">
      <header className="flex flex-wrap items-center justify-between gap-4 border-b px-6 py-5">
        <div><h1 className="text-xl font-semibold">ResolveOps 支持工作台</h1><p className="text-sm text-muted-foreground">工单、调查记录与可追溯证据</p></div>
        <div className="flex items-center gap-4"><Button variant="outline" onClick={() => setRefresh((value) => value + 1)}>刷新</Button><Link className="text-sm underline" to="/chat">返回聊天</Link></div>
      </header>
      <p className="border-b bg-muted/40 px-6 py-3 text-sm">当前为只读开发阶段。模型调查、业务审批与修复尚未启用；页面不会模拟调查结果或执行修改。</p>
      {error && <p role="alert" className="m-6 rounded border border-destructive p-4 text-destructive">{error}</p>}
      <div className="grid gap-6 p-6 lg:grid-cols-[260px_1fr_340px]">
        <nav aria-label="工单列表" className="space-y-3">
          <h2 className="font-semibold">可访问工单</h2>
          {!loading && !cases.length && <p className="text-sm text-muted-foreground">没有可访问的工单。请确认业务数据和客户访问授权已配置。</p>}
          {cases.map((record) => <Link key={record.id} to={`/support/${encodeURIComponent(record.id)}`} aria-current={record.id === caseId ? "page" : undefined}
            className={`block rounded-lg border p-3 text-sm ${record.id === caseId ? "border-primary bg-muted" : "hover:bg-muted/50"}`}>
            <span className="block font-medium">{scenarioName[record.scenario]}</span><span className="block break-all text-muted-foreground">{record.id}</span>
          </Link>)}
        </nav>
        <section aria-label="工单详情" className="min-w-0 space-y-5">
          {loading && <p role="status">正在读取工单…</p>}
          {detail && <>
            <div className="rounded-lg border p-5"><h2 className="text-lg font-semibold">{scenarioName[detail.scenario]}</h2>
              <p className="mt-3 whitespace-pre-wrap">{detail.description}</p>
              <dl className="mt-4 grid grid-cols-2 gap-2 text-sm"><dt>客户</dt><dd className="break-all">{detail.tenantId}</dd><dt>业务资源</dt><dd className="break-all">{detail.resourceId}</dd><dt>工单状态</dt><dd>{detail.status}</dd></dl>
            </div>
            <div className="space-y-3"><h2 className="font-semibold">调查记录</h2>
              {!runs.length && <p className="text-sm text-muted-foreground">尚无调查记录。模型接入完成后，真实运行及其证据会显示在这里。</p>}
              {runs.length > 0 && <select aria-label="选择调查记录" value={runId ?? ""} onChange={(event) => setRunId(event.target.value)} className="w-full rounded border bg-background p-2 text-sm">
                {runs.map((run) => <option key={run.id} value={run.id}>{run.status} · {run.startedAt}</option>)}
              </select>}
              {selectedRun && <div className="rounded-lg border p-4"><p className="text-sm">运行状态：{selectedRun.status}</p>
                <pre className="mt-3 overflow-auto whitespace-pre-wrap break-words text-xs">{JSON.stringify(selectedRun.diagnosis, null, 2)}</pre>
              </div>}
              {events.length > 0 && <ol className="space-y-2 text-sm">{events.map((event) => <li key={event.sequence} className="rounded border p-3">{event.type}<pre className="mt-2 whitespace-pre-wrap break-words text-xs">{JSON.stringify(event.payload, null, 2)}</pre></li>)}</ol>}
            </div>
          </>}
        </section>
        <aside aria-label="运行证据" className="min-w-0 space-y-3"><h2 className="font-semibold">运行证据</h2>
          {!evidence.length && <p className="text-sm text-muted-foreground">选择真实调查记录后查看。未产生证据时保持为空。</p>}
          {evidence.map((item) => <details key={item.id} className="rounded-lg border p-3 text-sm"><summary className="cursor-pointer break-all">{item.kind} · {item.sourceRef}</summary>
            <p className="mt-2 break-all text-xs text-muted-foreground">观测时间：{item.observedAt}<br />内容标识：{item.hash}</p>
            <pre className="mt-3 whitespace-pre-wrap break-words text-xs">{JSON.stringify(item.snapshot, null, 2)}</pre>
          </details>)}
        </aside>
      </div>
    </main>
  );
}
