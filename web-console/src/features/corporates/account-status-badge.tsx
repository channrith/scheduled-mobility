"use client";

import { useTranslations } from "next-intl";
import { Badge } from "@/components/ui/badge";
import type { AccountStatus } from "@/lib/api/types";
import { cn } from "@/lib/utils";

const tone: Record<AccountStatus, string> = {
  ACTIVE: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-200",
  SUSPENDED: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-200",
};

export function AccountStatusBadge({ status }: { status: AccountStatus }) {
  const t = useTranslations("accountStatus");
  return <Badge className={cn("border-0", tone[status])}>{t(status)}</Badge>;
}
