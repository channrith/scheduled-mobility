"use client";

import { AlertTriangle, Check, X } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { ReasonDialog } from "@/components/reason-dialog";
import { useSession } from "@/components/shell/session";
import { DocumentStatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ApiError } from "@/lib/api/client";
import { useReviewDocument } from "@/lib/api/hooks";
import type { DocumentView } from "@/lib/api/types";
import { daysUntil, useDates } from "@/lib/format";
import { DocumentPreview } from "./document-preview";

/** A current document with its sides, expiry and (for reviewers) approve/reject. */
export function DocumentCard({ driverId, document, onReviewed }: { driverId: string; document: DocumentView; onReviewed?: () => void }) {
  const t = useTranslations();
  const dates = useDates();
  const session = useSession();
  const review = useReviewDocument(driverId);
  const [rejecting, setRejecting] = useState(false);
  const days = daysUntil(document.expiresOn);
  const onError = (e: unknown) => toast.error(e instanceof ApiError && e.detail ? e.detail : t("common.error"));
  const done = () => {
    toast.success(t("actions.done"));
    onReviewed?.();
  };

  return (
    <Card size="sm">
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle className="leading-normal">{t(`docType.${document.type}`)}</CardTitle>
        <DocumentStatusBadge status={document.status} />
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-wrap gap-x-6 gap-y-1 text-sm text-muted-foreground">
          {document.expiresOn && (
            <span className={days !== null && days < 0 ? "text-destructive" : days !== null && days <= 30 ? "text-amber-600" : ""}>
              {(days !== null && days <= 30) && <AlertTriangle className="mr-1 inline size-3.5" />}
              {t("document.expiresOn")}: {dates.date(document.expiresOn)}
              {days !== null && days < 0 && ` · ${t("document.expired")}`}
            </span>
          )}
          <span>{t("document.uploaded")}: {dates.dateTime(document.uploadedAt)}</span>
          {document.uploadedBy && <span>{t("document.uploadedBy")}</span>}
        </div>
        {document.status === "REJECTED" && document.rejectionReason && (
          <p className="rounded-md bg-destructive/10 p-2 text-sm text-destructive">{document.rejectionReason}</p>
        )}
        {session.can("viewDocumentFiles") && <DocumentPreview driverId={driverId} document={document} />}
        {session.can("reviewDocuments") && document.status === "PENDING_REVIEW" && (
          <div className="flex gap-2">
            <Button
              disabled={review.isPending}
              onClick={() => review.mutate({ documentId: document.id, approve: true }, { onSuccess: done, onError })}
            >
              <Check /> {t("document.approve")}
            </Button>
            <Button variant="outline" disabled={review.isPending} onClick={() => setRejecting(true)}>
              <X /> {t("document.reject")}
            </Button>
          </div>
        )}
        <ReasonDialog
          open={rejecting}
          onOpenChange={setRejecting}
          title={t("document.rejectTitle")}
          description={t("document.rejectHint")}
          confirmLabel={t("document.reject")}
          destructive
          pending={review.isPending}
          onConfirm={({ reason }) =>
            review.mutate(
              { documentId: document.id, approve: false, reason },
              { onSuccess: () => { setRejecting(false); done(); }, onError },
            )
          }
        />
      </CardContent>
    </Card>
  );
}
