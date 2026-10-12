"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { ReasonDialog } from "@/components/reason-dialog";
import { useSession } from "@/components/shell/session";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api/client";
import { useDriverAction, type DriverAction } from "@/lib/api/hooks";
import type { DriverDetail } from "@/lib/api/types";
import type { Permission } from "@/lib/auth/roles";

type ActionDef = { action: DriverAction; permission: Permission; reason?: "plain" | "notice"; destructive?: boolean };

/** Available onboarding actions per status, matching the backend state machine. */
const BY_STATUS: Record<string, ActionDef[]> = {
  PENDING: [
    { action: "submit-documents", permission: "onboardingActions" },
    { action: "reject", permission: "onboardingActions", reason: "plain", destructive: true },
  ],
  DOCS_SUBMITTED: [
    { action: "training", permission: "onboardingActions" },
    { action: "reject", permission: "onboardingActions", reason: "plain", destructive: true },
  ],
  TRAINING: [
    { action: "approve", permission: "onboardingActions" },
    { action: "reject", permission: "onboardingActions", reason: "plain", destructive: true },
  ],
  APPROVED: [{ action: "suspend", permission: "suspend", reason: "notice", destructive: true }],
  SUSPENDED: [{ action: "reinstate", permission: "reinstate", reason: "plain" }],
  REJECTED: [],
};

export function DriverActions({ driver }: { driver: DriverDetail }) {
  const t = useTranslations();
  const session = useSession();
  const mutation = useDriverAction(driver.id);
  const [dialog, setDialog] = useState<ActionDef | null>(null);
  const actions = (BY_STATUS[driver.status] ?? []).filter((a) => session.can(a.permission));

  const run = (def: ActionDef, values?: { reason: string; noticeAt?: string }) =>
    mutation.mutate(
      { action: def.action, reason: values?.reason, noticeAt: values?.noticeAt },
      {
        onSuccess: () => {
          setDialog(null);
          toast.success(t("actions.done"));
        },
        onError: (e) => toast.error(e instanceof ApiError && e.detail ? e.detail : t("common.error")),
      },
    );

  return (
    <>
      {actions.map((def) => (
        <Button
          key={def.action}
          variant={def.destructive ? "outline" : "default"}
          className={def.destructive ? "text-destructive" : undefined}
          disabled={mutation.isPending}
          onClick={() => (def.reason ? setDialog(def) : run(def))}
        >
          {t(`actions.${def.action}`)}
        </Button>
      ))}
      {dialog && (
        <ReasonDialog
          open
          onOpenChange={(open) => !open && setDialog(null)}
          title={t(`actions.${dialog.action}Title` as "actions.rejectTitle")}
          description={dialog.reason === "notice" ? t("actions.suspendHint") : undefined}
          confirmLabel={t(`actions.${dialog.action}`)}
          withNoticeTime={dialog.reason === "notice"}
          destructive={dialog.destructive}
          pending={mutation.isPending}
          onConfirm={(values) => run(dialog, values)}
        />
      )}
    </>
  );
}
