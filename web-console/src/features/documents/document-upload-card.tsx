"use client";

import { CheckCircle2 } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { Field } from "@/components/field";
import { DocumentStatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ApiError } from "@/lib/api/client";
import { useUploadDocument } from "@/lib/api/hooks";
import { EXPIRING, TWO_SIDED, type DocumentType, type DocumentView } from "@/lib/api/types";
import { FilePicker } from "./file-picker";

/** Upload (or replace) one document type on the driver's behalf: front, back and expiry date. */
export function DocumentUploadCard({
  driverId,
  type,
  vehicleId,
  current,
}: {
  driverId: string;
  type: DocumentType;
  vehicleId?: string;
  current?: DocumentView;
}) {
  const t = useTranslations();
  const upload = useUploadDocument(driverId);
  const [front, setFront] = useState<File | null>(null);
  const [back, setBack] = useState<File | null>(null);
  const [expiresOn, setExpiresOn] = useState("");
  const [editing, setEditing] = useState(!current || current.status === "REJECTED");

  const twoSided = TWO_SIDED.includes(type);
  const frontIsPdf = front?.type === "application/pdf";
  const needsBack = twoSided && !frontIsPdf;
  const needsExpiry = EXPIRING.includes(type);
  const ready = !!front && (!needsBack || !!back) && (!needsExpiry || !!expiresOn);

  const submit = () => {
    const form = new FormData();
    form.set("type", type);
    if (vehicleId) form.set("vehicleId", vehicleId);
    if (needsExpiry) form.set("expiresOn", expiresOn);
    form.set("front", front!);
    if (needsBack && back) form.set("back", back);
    upload.mutate(form, {
      onSuccess: () => {
        toast.success(t("document.uploaded"));
        setFront(null);
        setBack(null);
        setExpiresOn("");
        setEditing(false);
      },
      onError: (e) => toast.error(e instanceof ApiError && e.detail ? e.detail : t("common.error")),
    });
  };

  return (
    <Card size="sm">
      <CardHeader className="flex flex-row items-center justify-between gap-2">
        <CardTitle className="leading-normal">{t(`docType.${type}`)}</CardTitle>
        {current && <DocumentStatusBadge status={current.status} />}
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {current && current.status === "REJECTED" && current.rejectionReason && (
          <p className="rounded-md bg-destructive/10 p-2 text-sm text-destructive">{current.rejectionReason}</p>
        )}
        {!editing ? (
          <div className="flex items-center justify-between gap-2 text-sm">
            <span className="inline-flex items-center gap-1.5 text-muted-foreground">
              <CheckCircle2 className="size-4 text-emerald-600" /> {t("document.uploaded")}
            </span>
            <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
              {t("document.replace")}
            </Button>
          </div>
        ) : (
          <>
            <div className={twoSided ? "grid grid-cols-1 gap-3 sm:grid-cols-2" : "grid grid-cols-1 gap-3 sm:max-w-sm"}>
              <FilePicker
                label={twoSided ? t("document.front") : t(`docType.${type}`)}
                value={front}
                onChange={setFront}
                allowPdf={type !== "PROFILE_PHOTO"}
              />
              {twoSided && !frontIsPdf && <FilePicker label={t("document.back")} value={back} onChange={setBack} />}
              {twoSided && frontIsPdf && (
                <p className="self-center text-sm text-muted-foreground">{t("document.pdfBothSides")}</p>
              )}
            </div>
            {needsExpiry && (
              <Field label={t("document.expiresOn")} htmlFor={`exp-${type}`} className="sm:max-w-xs">
                <Input id={`exp-${type}`} type="date" value={expiresOn} onChange={(e) => setExpiresOn(e.target.value)} />
              </Field>
            )}
            <div className="flex gap-2">
              <Button disabled={!ready || upload.isPending} onClick={submit}>
                {t("document.upload")}
              </Button>
              {current && (
                <Button variant="ghost" onClick={() => setEditing(false)}>
                  {t("common.cancel")}
                </Button>
              )}
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
