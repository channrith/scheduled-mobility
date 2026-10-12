"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { toLocalInput } from "@/lib/format";

/**
 * Asks for a reason (≥ 10 characters, the backend rule) and optionally when the driver was notified
 * (suspension due process).
 */
export function ReasonDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  withNoticeTime,
  destructive,
  pending,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  confirmLabel: string;
  withNoticeTime?: boolean;
  destructive?: boolean;
  pending?: boolean;
  onConfirm: (values: { reason: string; noticeAt?: string }) => void;
}) {
  const t = useTranslations();
  const schema = z.object({
    reason: z.string().trim().min(10, t("common.reasonHint")),
    noticeAt: z.string().optional(),
  });
  const form = useForm<z.infer<typeof schema>>({
    resolver: zodResolver(schema),
    values: { reason: "", noticeAt: toLocalInput(new Date()) },
  });
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <form
          onSubmit={form.handleSubmit((v) =>
            onConfirm({
              reason: v.reason.trim(),
              noticeAt: withNoticeTime && v.noticeAt ? new Date(v.noticeAt).toISOString() : undefined,
            }),
          )}
          className="flex flex-col gap-4"
        >
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            {description && <DialogDescription>{description}</DialogDescription>}
          </DialogHeader>
          <Field label={t("common.reason")} htmlFor="reason" hint={t("common.reasonHint")} error={form.formState.errors.reason?.message}>
            <Textarea id="reason" rows={4} autoFocus {...form.register("reason")} />
          </Field>
          {withNoticeTime && (
            <Field label={t("detail.noticeAt")} htmlFor="noticeAt">
              <Input id="noticeAt" type="datetime-local" max={toLocalInput(new Date())} {...form.register("noticeAt")} />
            </Field>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" variant={destructive ? "destructive" : "default"} disabled={pending}>
              {confirmLabel}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
