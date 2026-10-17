"use client";

import { History, Plus, Power } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { ErrorState, LoadingRows } from "@/components/page/state";
import { ReasonlessConfirm } from "@/components/confirm-dialog";
import { useSession } from "@/components/shell/session";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ApiError } from "@/lib/api/client";
import { usePriceLines, useRetireLine } from "@/lib/api/hooks";
import { VEHICLE_CLASSES, type PriceLineView, type VehicleClass } from "@/lib/api/types";
import { useDates } from "@/lib/format";
import { RuleVersionDialog } from "./rule-form";
import { RuleHistoryDialog } from "./rule-history";
import { RulePrice } from "./rule-price";

/**
 * One row per vehicle class for a city: the version in force, upcoming versions and actions. With
 * {@code corporateId} the rows are that company's overrides (the city price applies where there is none).
 */
export function PriceLines({ city, corporateId }: { city: string; corporateId?: string }) {
  const t = useTranslations();
  const dates = useDates();
  const session = useSession();
  const canManage = session.can("managePricing");
  const lines = usePriceLines({ city, corporateId });
  const retire = useRetireLine();
  const [editing, setEditing] = useState<{ vehicleClass: VehicleClass; line?: PriceLineView } | null>(null);
  const [history, setHistory] = useState<VehicleClass | null>(null);
  const [retiring, setRetiring] = useState<VehicleClass | null>(null);

  if (lines.isPending) return <LoadingRows rows={6} />;
  if (lines.isError) return <ErrorState error={lines.error} onRetry={() => lines.refetch()} />;
  const byClass = new Map(lines.data.map((l) => [l.vehicleClass, l]));
  const cityName = t.has(`city.${city}`) ? t(`city.${city}`) : city;

  return (
    <>
      <div className="overflow-x-auto rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("rules.vehicleClass")}</TableHead>
              <TableHead>{t("rules.price")}</TableHead>
              <TableHead>{t("rules.inForce")}</TableHead>
              <TableHead>{t("rules.upcoming")}</TableHead>
              <TableHead><span className="sr-only">{t("common.actions")}</span></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {VEHICLE_CLASSES.map((vc) => {
              const line = byClass.get(vc);
              const current = line?.current;
              return (
                <TableRow key={vc}>
                  <TableCell className="py-2.5 align-top font-medium">{t(`vehicleClass.${vc}`)}</TableCell>
                  <TableCell className="align-top whitespace-normal">
                    {current ? (
                      <RulePrice rule={current} />
                    ) : line && line.latest && !line.latest.active ? (
                      <span className="text-muted-foreground">{corporateId ? t("rules.overrideRetired") : t("rules.retiredLine")}</span>
                    ) : (
                      <span className="text-muted-foreground">{corporateId ? t("rules.noOverride") : t("rules.noPrice")}</span>
                    )}
                  </TableCell>
                  <TableCell className="align-top text-sm">
                    {current ? (
                      <div className="flex flex-col">
                        <span>
                          {t("rules.since", { date: dates.dateTime(current.validFrom) })}
                          {current.validTo && ` – ${dates.dateTime(current.validTo)}`}
                        </span>
                        <span className="text-xs text-muted-foreground">{current.createdBy?.name ?? "—"}</span>
                      </div>
                    ) : (
                      "—"
                    )}
                  </TableCell>
                  <TableCell className="align-top">
                    <div className="flex flex-col gap-1">
                      {(line?.upcoming ?? []).map((u) => (
                        <Badge key={u.id} variant="outline" className="h-auto w-fit py-0.5 whitespace-normal">
                          {t("rules.from", { date: dates.dateTime(u.validFrom) })}
                        </Badge>
                      ))}
                      {(line?.upcoming ?? []).length === 0 && <span className="text-muted-foreground">—</span>}
                    </div>
                  </TableCell>
                  <TableCell className="text-right align-top whitespace-nowrap">
                    {canManage && (
                      <Button variant="outline" size="sm" onClick={() => setEditing({ vehicleClass: vc, line })}>
                        <Plus /> {line ? t("rules.newVersion") : corporateId ? t("rules.addOverride") : t("rules.setPrice")}
                      </Button>
                    )}
                    {line && (
                      <Button variant="ghost" size="icon-sm" aria-label={t("rules.history")} onClick={() => setHistory(vc)}>
                        <History />
                      </Button>
                    )}
                    {canManage && line?.latest?.active && (
                      <Button variant="ghost" size="icon-sm" className="text-destructive" aria-label={t("rules.retire")} onClick={() => setRetiring(vc)}>
                        <Power />
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>
      {editing && (
        <RuleVersionDialog
          city={city}
          vehicleClass={editing.vehicleClass}
          corporateId={corporateId}
          latest={editing.line?.latest}
          onClose={() => setEditing(null)}
        />
      )}
      {history && (
        <RuleHistoryDialog
          line={{ city, vehicleClass: history, corporateId }}
          title={t("rules.historyTitle", { vehicleClass: t(`vehicleClass.${history}`), city: cityName })}
          onClose={() => setHistory(null)}
        />
      )}
      {retiring && (
        <ReasonlessConfirm
          title={t("rules.retireTitle", { vehicleClass: t(`vehicleClass.${retiring}`) })}
          description={corporateId ? t("rules.retireOverrideHint") : t("rules.retireHint")}
          confirmLabel={t("rules.retire")}
          pending={retire.isPending}
          onCancel={() => setRetiring(null)}
          onConfirm={() =>
            retire.mutate(
              { city, vehicleClass: retiring, corporateId },
              {
                onSuccess: () => {
                  setRetiring(null);
                  toast.success(t("common.saved"));
                },
                onError: (e) => toast.error(e instanceof ApiError && e.detail ? e.detail : t("common.error")),
              },
            )
          }
        />
      )}
    </>
  );
}
