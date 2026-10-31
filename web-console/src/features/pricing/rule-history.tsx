"use client";

import { useTranslations } from "next-intl";
import { ErrorState, LoadingRows } from "@/components/page/state";
import { Badge } from "@/components/ui/badge";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useRuleHistory } from "@/lib/api/hooks";
import type { VehicleClass } from "@/lib/api/types";
import { useDates } from "@/lib/format";
import { cn } from "@/lib/utils";
import { RulePrice } from "./rule-price";

const tone: Record<string, string> = {
  CURRENT: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-200",
  UPCOMING: "bg-sky-100 text-sky-800 dark:bg-sky-950 dark:text-sky-200",
  PAST: "bg-zinc-200 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300",
  NEVER_IN_FORCE: "bg-zinc-100 text-zinc-500 dark:bg-zinc-900 dark:text-zinc-400",
  RETIREMENT: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-200",
};

/** Every version of a line, newest first, with when each is (or was) in force and who created it. */
export function RuleHistoryDialog({
  line,
  title,
  onClose,
}: {
  line: { city: string; vehicleClass: VehicleClass; corporateId?: string };
  title: string;
  onClose: () => void;
}) {
  const t = useTranslations();
  const dates = useDates();
  const history = useRuleHistory(line);
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        {history.isPending ? (
          <LoadingRows rows={3} />
        ) : history.isError ? (
          <ErrorState error={history.error} onRetry={() => history.refetch()} />
        ) : (
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("rules.period")}</TableHead>
                  <TableHead>{t("rules.price")}</TableHead>
                  <TableHead>{t("rules.inForce")}</TableHead>
                  <TableHead>{t("rules.createdBy")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {history.data.map((h) => (
                  <TableRow key={h.rule.id}>
                    <TableCell className="py-2.5 align-top">
                      <Badge className={cn("border-0", tone[h.period])}>{t(`rulePeriod.${h.period}`)}</Badge>
                    </TableCell>
                    <TableCell className="align-top whitespace-normal">
                      {h.rule.active ? <RulePrice rule={h.rule} /> : <span className="text-muted-foreground">{t("rules.retiredLine")}</span>}
                    </TableCell>
                    <TableCell className="align-top text-sm">
                      {dates.dateTime(h.rule.validFrom)} – {h.effectiveUntil ? dates.dateTime(h.effectiveUntil) : t("rules.openEnded")}
                    </TableCell>
                    <TableCell className="align-top text-sm">
                      <div className="flex flex-col">
                        <span>{h.rule.createdBy?.name ?? "—"}</span>
                        <span className="text-xs text-muted-foreground">{dates.dateTime(h.rule.createdAt)}</span>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
