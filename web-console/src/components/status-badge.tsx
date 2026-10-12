"use client";

import { useTranslations } from "next-intl";
import { Badge } from "@/components/ui/badge";
import type { DocumentStatus, DriverStatus } from "@/lib/api/types";
import { cn } from "@/lib/utils";

const driverTone: Record<DriverStatus, string> = {
  PENDING: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200",
  DOCS_SUBMITTED: "bg-sky-100 text-sky-800 dark:bg-sky-950 dark:text-sky-200",
  TRAINING: "bg-violet-100 text-violet-800 dark:bg-violet-950 dark:text-violet-200",
  APPROVED: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-200",
  REJECTED: "bg-zinc-200 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300",
  SUSPENDED: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-200",
};

const documentTone: Record<DocumentStatus, string> = {
  PENDING_REVIEW: "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-200",
  APPROVED: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-200",
  REJECTED: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-200",
  SUPERSEDED: "bg-zinc-200 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300",
};

export function DriverStatusBadge({ status }: { status: DriverStatus }) {
  const t = useTranslations("status");
  return <Badge className={cn("border-0", driverTone[status])}>{t(status)}</Badge>;
}

export function DocumentStatusBadge({ status }: { status: DocumentStatus }) {
  const t = useTranslations("docStatus");
  return <Badge className={cn("border-0", documentTone[status])}>{t(status)}</Badge>;
}
