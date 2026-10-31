"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { Field } from "@/components/field";
import { NativeSelect } from "@/components/native-select";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { ApiError } from "@/lib/api/client";
import { useCreateRule } from "@/lib/api/hooks";
import { CURRENCIES, type Currency, type RuleView, type VehicleClass } from "@/lib/api/types";
import { toLocalInput } from "@/lib/format";
import { fromMinor, toMinor } from "@/lib/money";

type Draft = {
  currency: Currency;
  base: string;
  perKm: string;
  perMin: string;
  minFare: string;
  fuelIndexFactor: string;
  maxSurge: string;
  validFrom: string;
  validTo: string;
};

function draftFrom(rule: RuleView | null | undefined): Draft {
  const currency = (rule?.currency as Currency | undefined) ?? "USD";
  const money = (minor: number | undefined) => (minor === undefined ? "" : fromMinor(minor, currency));
  return {
    currency,
    base: money(rule?.baseMinor),
    perKm: money(rule?.perKmMinor),
    perMin: money(rule?.perMinMinor),
    minFare: money(rule?.minFareMinor),
    fuelIndexFactor: rule ? String(Number(rule.fuelIndexFactor)) : "1",
    maxSurge: rule ? String(Number(rule.maxSurge)) : "1.5",
    validFrom: "",
    validTo: "",
  };
}

/**
 * A new version of a price line. Prefilled from the latest version; existing versions are never changed.
 * Amounts are typed in dollars or riel and sent in minor units.
 */
export function RuleVersionDialog({
  city,
  vehicleClass,
  corporateId,
  latest,
  onClose,
}: {
  city: string;
  vehicleClass: VehicleClass;
  corporateId?: string;
  latest?: RuleView | null;
  onClose: () => void;
}) {
  const t = useTranslations();
  const create = useCreateRule();
  const [draft, setDraft] = useState<Draft>(() => draftFrom(latest?.active ? latest : null));
  const [errors, setErrors] = useState<Partial<Record<keyof Draft, boolean>>>({});
  const set = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }));

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const minor = (v: string) => toMinor(v.trim() || "x", draft.currency);
    const values = { base: minor(draft.base), perKm: minor(draft.perKm), perMin: minor(draft.perMin), minFare: minor(draft.minFare) };
    const factor = Number(draft.fuelIndexFactor);
    const surge = Number(draft.maxSurge);
    const next: Partial<Record<keyof Draft, boolean>> = {
      base: Number.isNaN(values.base),
      perKm: Number.isNaN(values.perKm),
      perMin: Number.isNaN(values.perMin),
      minFare: Number.isNaN(values.minFare),
      fuelIndexFactor: !/^\d(\.\d{1,3})?$/.test(draft.fuelIndexFactor.trim()) || factor > 2,
      maxSurge: !/^\d(\.\d{1,2})?$/.test(draft.maxSurge.trim()) || surge < 1 || surge > 5,
      validTo: !!draft.validTo && !!draft.validFrom && draft.validTo <= draft.validFrom,
    };
    setErrors(next);
    if (Object.values(next).some(Boolean)) return;
    create.mutate(
      {
        city,
        vehicleClass,
        corporateId,
        currency: draft.currency,
        baseMinor: values.base,
        perKmMinor: values.perKm,
        perMinMinor: values.perMin,
        minFareMinor: values.minFare,
        fuelIndexFactor: factor,
        maxSurge: surge,
        validFrom: draft.validFrom ? new Date(draft.validFrom).toISOString() : undefined,
        validTo: draft.validTo ? new Date(draft.validTo).toISOString() : undefined,
      },
      {
        onSuccess: () => {
          toast.success(t("common.saved"));
          onClose();
        },
        onError: (err) => toast.error(err instanceof ApiError && err.detail ? err.detail : t("common.error")),
      },
    );
  };

  const amount = (key: "base" | "perKm" | "perMin" | "minFare", label: string) => (
    <Field label={`${label} (${draft.currency})`} htmlFor={`rule-${key}`} error={errors[key] ? t("corporates.amountInvalid") : undefined}>
      <Input id={`rule-${key}`} inputMode="decimal" value={draft[key]} onChange={(e) => set({ [key]: e.target.value })} />
    </Field>
  );

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-2xl">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t("rules.newVersionTitle", { vehicleClass: t(`vehicleClass.${vehicleClass}`), city: t.has(`city.${city}`) ? t(`city.${city}`) : city })}</DialogTitle>
            <DialogDescription>{t("rules.newVersionHint")}</DialogDescription>
          </DialogHeader>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label={t("corporates.currency")} htmlFor="rule-currency">
              <NativeSelect id="rule-currency" value={draft.currency} onChange={(e) => set({ currency: e.target.value as Currency })}>
                {CURRENCIES.map((c) => (
                  <option key={c} value={c}>{t(`currency.${c}`)}</option>
                ))}
              </NativeSelect>
            </Field>
            <div className="hidden sm:block" />
            {amount("base", t("rules.base"))}
            {amount("minFare", t("rules.minFare"))}
            {amount("perKm", t("rules.perKm"))}
            {amount("perMin", t("rules.perMin"))}
            <Field label={t("rules.fuelIndexFactor")} htmlFor="rule-fuel" hint={t("rules.fuelIndexFactorHint")} error={errors.fuelIndexFactor ? t("rules.fuelIndexFactorRange") : undefined}>
              <Input id="rule-fuel" inputMode="decimal" value={draft.fuelIndexFactor} onChange={(e) => set({ fuelIndexFactor: e.target.value })} />
            </Field>
            <Field label={t("rules.maxSurge")} htmlFor="rule-surge" hint={t("rules.maxSurgeHint")} error={errors.maxSurge ? t("rules.maxSurgeRange") : undefined}>
              <Input id="rule-surge" inputMode="decimal" value={draft.maxSurge} onChange={(e) => set({ maxSurge: e.target.value })} />
            </Field>
            <Field label={t("rules.validFrom")} htmlFor="rule-from" optional optionalText={t("rules.now")} hint={t("rules.validFromHint")}>
              <Input id="rule-from" type="datetime-local" min={toLocalInput(new Date())} value={draft.validFrom} onChange={(e) => set({ validFrom: e.target.value })} />
            </Field>
            <Field label={t("rules.validTo")} htmlFor="rule-to" optional optionalText={t("rules.openEnded")} error={errors.validTo ? t("rules.validToInvalid") : undefined}>
              <Input id="rule-to" type="datetime-local" min={draft.validFrom || toLocalInput(new Date())} value={draft.validTo} onChange={(e) => set({ validTo: e.target.value })} />
            </Field>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t("common.cancel")}</Button>
            <Button type="submit" disabled={create.isPending}>{t("rules.createVersion")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
