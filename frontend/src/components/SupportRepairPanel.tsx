import { isAxiosError } from "axios";
import { useEffect, useState } from "react";

import { Button } from "@/components/ui/button";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription,
  AlertDialogFooter, AlertDialogTitle, AlertDialogTrigger } from "@/components/ui/alert-dialog";
import { supportService } from "@/services/supportService";
import type { SupportRepair } from "@/types/support";

const statusLabels: Record<string, string> = {
  PENDING_APPROVAL: "待审批",
  APPROVED: "已审批，待执行",
  REJECTED: "已拒绝",
  EXPIRED: "已过期",
  STALE: "已失效",
  VERIFIED: "已修复并核验",
};

function errorMessage(error: unknown) {
  const code = isAxiosError(error) ? error.response?.data?.code : null;
  switch (code) {
    case "SUPPORT_REPAIR_STALE": return "提案引用的记录已变更。请重新调查。";
    case "SUPPORT_REPAIR_EXPIRED": return "提案已过期。请重新调查。";
    case "SUPPORT_NOT_FOUND_OR_FORBIDDEN": return "你或审批人的权限已变更，请核对权限。";
    case "SUPPORT_CONFLICT": return "提案状态已变更。请刷新后重试。";
    default: return "暂时无法确认提案状态。请刷新查看是否已保存。";
  }
}

export function SupportRepairPanel({ runId }: { runId: string }) {
  const [repair, setRepair] = useState<SupportRepair | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let current = true;
    setBusy(true);
    setError(null);
    supportService.repair(runId)
      .then((view) => { if (current) setRepair(view); })
      .catch((cause: unknown) => {
        if (current) {
          setRepair(null);
          setError(errorMessage(cause));
        }
      })
      .finally(() => { if (current) setBusy(false); });
    return () => { current = false; };
  }, [runId, revision]);

  const proposal = repair?.proposal;
  const act = async (action: "propose" | "approve" | "reject" | "execute") => {
    const payloadHash = proposal?.payloadHash;
    if (busy || (action !== "propose" && !payloadHash)) return;
    setBusy(true);
    setError(null);
    try {
      const next = action === "propose"
        ? await supportService.proposeRepair(runId)
        : await supportService.decideRepair(runId, action, payloadHash ?? "");
      setRepair(next);
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  };

  return <section aria-label="订阅修复提案" className="mt-4 space-y-2 border-t pt-4 text-sm">
    <div className="flex items-center justify-between gap-2">
      <h4 className="font-medium">订阅修复提案</h4>
      <Button size="sm" variant="ghost" disabled={busy} onClick={() => setRevision((value) => value + 1)}>刷新提案</Button>
    </div>
    {busy && <p role="status">处理中…</p>}
    {error && <p role="alert">{error}</p>}
    {proposal && <>
      <p>{statusLabels[proposal.status] ?? proposal.status}</p>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 break-all">
        <dt>订阅</dt><dd>{proposal.targetId}</dd>
        <dt>待核对版本</dt><dd>{proposal.expectedVersion}</dd>
        <dt>付款和失败事件</dt><dd>{proposal.paymentEventId} / {proposal.failureEventId}</dd>
        <dt>有效期至</dt><dd>{new Date(proposal.expiresAt).toLocaleString()}</dd>
        {proposal.approverId && <><dt>复核人</dt><dd>{proposal.approverId}</dd></>}
        {proposal.receipt && <>
          <dt>核验结果</dt><dd>已开通，版本 {proposal.receipt.subscriptionVersion}</dd>
          <dt>操作回执</dt><dd>{proposal.receipt.operationId}</dd>
          <dt>修复事件</dt><dd>{proposal.receipt.eventId}</dd>
        </>}
      </dl>
    </>}
    <div className="flex flex-wrap gap-2">
      {repair?.canPropose && <Button size="sm" disabled={busy} onClick={() => void act("propose")}>提交修复提案</Button>}
      {repair?.canReview && <>
        <Button size="sm" disabled={busy} onClick={() => void act("approve")}>批准此提案</Button>
        <Button size="sm" variant="outline" disabled={busy} onClick={() => void act("reject")}>拒绝此提案</Button>
      </>}
      {repair?.canExecute && proposal && <AlertDialog>
        <AlertDialogTrigger asChild><Button size="sm" disabled={busy}>执行订阅修复</Button></AlertDialogTrigger>
        <AlertDialogContent>
          <AlertDialogTitle>开通已审批的订阅</AlertDialogTitle>
          <AlertDialogDescription>
            将订阅 {proposal.targetId}（版本 {proposal.expectedVersion}）标记为已开通，并生成核验回执。
          </AlertDialogDescription>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={() => void act("execute")}>确认执行</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>}
    </div>
  </section>;
}
