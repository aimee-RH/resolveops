import { isAxiosError } from "axios";
import { useEffect, useState } from "react";

import { Button } from "@/components/ui/button";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription,
  AlertDialogFooter, AlertDialogTitle, AlertDialogTrigger } from "@/components/ui/alert-dialog";
import { supportService } from "@/services/supportService";
import type { SupportRepair } from "@/types/support";

const statusLabels: Record<string, string> = {
  PENDING_APPROVAL: "Awaiting approval",
  APPROVED: "Approved, ready to execute",
  REJECTED: "Rejected",
  EXPIRED: "Expired",
  STALE: "Evidence changed",
  VERIFIED: "Repaired and verified",
};

function errorMessage(error: unknown) {
  const code = isAxiosError(error) ? error.response?.data?.code : null;
  switch (code) {
    case "SUPPORT_REPAIR_STALE": return "The records referenced by this proposal have changed. Start a new investigation.";
    case "SUPPORT_REPAIR_EXPIRED": return "This proposal has expired. Start a new investigation.";
    case "SUPPORT_NOT_FOUND_OR_FORBIDDEN": return "Access is unavailable. Check your permissions and the reviewer's permissions.";
    case "SUPPORT_CONFLICT": return "The proposal status has changed. Refresh and try again.";
    default: return "Unable to confirm the proposal status. Refresh to check whether it was saved.";
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

  return <section aria-label="Subscription repair proposal" className="mt-4 space-y-2 border-t pt-4 text-sm">
    <div className="flex items-center justify-between gap-2">
      <h4 className="font-medium">Subscription repair proposal</h4>
      <Button size="sm" variant="ghost" disabled={busy} onClick={() => setRevision((value) => value + 1)}>Refresh proposal</Button>
    </div>
    {busy && <p role="status">Processing…</p>}
    {error && <p role="alert">{error}</p>}
    {proposal && <>
      <p>{statusLabels[proposal.status] ?? proposal.status}</p>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 break-all">
        <dt>Subscription</dt><dd>{proposal.targetId}</dd>
        <dt>Expected version</dt><dd>{proposal.expectedVersion}</dd>
        <dt>Payment / failure events</dt><dd>{proposal.paymentEventId} / {proposal.failureEventId}</dd>
        <dt>Expires at</dt><dd>{new Date(proposal.expiresAt).toLocaleString("en-US")}</dd>
        {proposal.approverId && <><dt>Reviewer</dt><dd>{proposal.approverId}</dd></>}
        {proposal.receipt && <>
          <dt>Verified result</dt><dd>Active, version {proposal.receipt.subscriptionVersion}</dd>
          <dt>Operation receipt</dt><dd>{proposal.receipt.operationId}</dd>
          <dt>Repair event</dt><dd>{proposal.receipt.eventId}</dd>
        </>}
      </dl>
    </>}
    <div className="flex flex-wrap gap-2">
      {repair?.canPropose && <Button size="sm" disabled={busy} onClick={() => void act("propose")}>Submit repair proposal</Button>}
      {repair?.canReview && <>
        <Button size="sm" disabled={busy} onClick={() => void act("approve")}>Approve proposal</Button>
        <Button size="sm" variant="outline" disabled={busy} onClick={() => void act("reject")}>Reject proposal</Button>
      </>}
      {repair?.canExecute && proposal && <AlertDialog>
        <AlertDialogTrigger asChild><Button size="sm" disabled={busy}>Execute repair</Button></AlertDialogTrigger>
        <AlertDialogContent className="support-repair-dialog">
          <AlertDialogTitle>Activate the approved subscription</AlertDialogTitle>
          <AlertDialogDescription>
            Activate subscription {proposal.targetId} at expected version {proposal.expectedVersion} and generate a verified receipt.
          </AlertDialogDescription>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={() => void act("execute")}>Confirm execution</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>}
    </div>
  </section>;
}
