"use client";

import { Pencil } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { Field } from "@/components/field";
import { PageHeader } from "@/components/page/page-header";
import { ErrorState, LoadingRows, NotAllowed } from "@/components/page/state";
import { useSession } from "@/components/shell/session";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ApiError } from "@/lib/api/client";
import { usePricingSettings, useSetPricingSetting } from "@/lib/api/hooks";
import type { SettingView } from "@/lib/api/types";
import { useDates } from "@/lib/format";

type Kind = "fuel-index" | "exchange-rate";

const FORMAT: Record<Kind, (v: number) => string> = {
  "fuel-index": (v) => v.toFixed(3),
  "exchange-rate": (v) => `1 USD = ${new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 }).format(v)} ៛`,
};

/** Fuel index and USD→KHR rate, with who changed them and when. */
export function PricingSettingsPage() {
  const t = useTranslations();
  const session = useSession();
  const settings = usePricingSettings();
  const [editing, setEditing] = useState<Kind | null>(null);
  if (!session.can("viewPricing")) return <NotAllowed />;
  if (settings.isPending) return <LoadingRows />;
  if (settings.isError) return <ErrorState error={settings.error} onRetry={() => settings.refetch()} />;
  const s = settings.data;

  return (
    <div className="flex flex-col gap-4">
      <PageHeader title={t("pricingSettings.title")} description={t("pricingSettings.subtitle")} />
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        <SettingCard kind="fuel-index" current={s.fuelIndex} history={s.fuelIndexHistory} onEdit={() => setEditing("fuel-index")} />
        <SettingCard kind="exchange-rate" current={s.usdKhrRate} history={s.usdKhrRateHistory} onEdit={() => setEditing("exchange-rate")} />
      </div>
      {editing && (
        <SettingDialog kind={editing} current={editing === "fuel-index" ? s.fuelIndex : s.usdKhrRate} onClose={() => setEditing(null)} />
      )}
    </div>
  );
}

function SettingCard({ kind, current, history, onEdit }: { kind: Kind; current: SettingView; history: SettingView[]; onEdit: () => void }) {
  const t = useTranslations();
  const dates = useDates();
  const session = useSession();
  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-2">
        <div className="flex flex-col gap-1">
          <CardTitle>{t(`pricingSettings.${kind}`)}</CardTitle>
          <CardDescription>{t(`pricingSettings.${kind}Hint`)}</CardDescription>
        </div>
        {session.can("managePricing") && (
          <Button variant="outline" size="sm" onClick={onEdit}>
            <Pencil /> {t("pricingSettings.change")}
          </Button>
        )}
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div>
          <p className="text-2xl font-semibold tabular-nums">{FORMAT[kind](Number(current.value))}</p>
          <p className="text-sm text-muted-foreground">
            {current.configuredDefault
              ? t("pricingSettings.configuredDefault")
              : t("pricingSettings.changedBy", { name: current.changedBy?.name ?? "—", date: dates.dateTime(current.changedAt) })}
          </p>
        </div>
        {history.length > 0 && (
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("pricingSettings.value")}</TableHead>
                  <TableHead>{t("pricingSettings.by")}</TableHead>
                  <TableHead>{t("detail.when")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {history.map((h, i) => (
                  <TableRow key={i}>
                    <TableCell className="py-2 tabular-nums">{FORMAT[kind](Number(h.value))}</TableCell>
                    <TableCell>{h.changedBy?.name ?? "—"}</TableCell>
                    <TableCell className="text-muted-foreground">{dates.dateTime(h.changedAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function SettingDialog({ kind, current, onClose }: { kind: Kind; current: SettingView; onClose: () => void }) {
  const t = useTranslations();
  const set = useSetPricingSetting();
  const [value, setValue] = useState(String(Number(current.value)));
  const [error, setError] = useState(false);
  const pattern = kind === "fuel-index" ? /^\d(\.\d{1,3})?$/ : /^\d{4,5}(\.\d{1,2})?$/;
  const inRange = (v: number) => (kind === "fuel-index" ? v >= 0.5 && v <= 3 : v >= 1000 && v <= 10000);
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-md">
        <form
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            const v = value.trim().replace(/,/g, "");
            if (!pattern.test(v) || !inRange(Number(v))) {
              setError(true);
              return;
            }
            set.mutate(
              { setting: kind, value: Number(v) },
              {
                onSuccess: () => {
                  toast.success(t("common.saved"));
                  onClose();
                },
                onError: (err) => toast.error(err instanceof ApiError && err.detail ? err.detail : t("common.error")),
              },
            );
          }}
        >
          <DialogHeader>
            <DialogTitle>{t(`pricingSettings.${kind}`)}</DialogTitle>
            <DialogDescription>{t("pricingSettings.appliesToNewQuotes")}</DialogDescription>
          </DialogHeader>
          <Field label={t("pricingSettings.value")} htmlFor="setting-value" hint={t(`pricingSettings.${kind}Range`)} error={error ? t(`pricingSettings.${kind}Range`) : undefined}>
            <Input id="setting-value" inputMode="decimal" autoFocus value={value} onChange={(e) => setValue(e.target.value)} />
          </Field>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t("common.cancel")}</Button>
            <Button type="submit" disabled={set.isPending}>{t("common.save")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
