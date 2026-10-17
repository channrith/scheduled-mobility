"use client";

import { Plus, Trash2 } from "lucide-react";
import { useTranslations } from "next-intl";
import { Field } from "@/components/field";
import { NativeSelect } from "@/components/native-select";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { CURRENCIES, DAYS, VEHICLE_CLASSES, type Currency, type DayOfWeek, type MemberPolicy, type VehicleClass } from "@/lib/api/types";
import { formatMoney, fromMinor, toMinor } from "@/lib/money";

/** Editable form of a member policy. Empty parts mean "no limit", as on the server. */
export type PolicyDraft = {
  classes: VehicleClass[];
  windows: { days: DayOfWeek[]; from: string; to: string }[];
  maxFare: string;
  currency: Currency;
};

export function toDraft(policy: MemberPolicy | null | undefined, defaultCurrency: Currency): PolicyDraft {
  const currency = (policy?.maxFare?.currency as Currency | undefined) ?? defaultCurrency;
  return {
    classes: [...(policy?.allowedVehicleClasses ?? [])],
    windows: (policy?.allowedHours ?? []).map((w) => ({
      days: [...(w.days ?? [])],
      from: (w.from ?? "").slice(0, 5),
      to: (w.to ?? "").slice(0, 5),
    })),
    maxFare: policy?.maxFare?.amountMinor ? fromMinor(policy.maxFare.amountMinor, currency) : "",
    currency,
  };
}

/** @returns the policy, or the name of the first invalid part */
export function fromDraft(draft: PolicyDraft): { policy: MemberPolicy } | { error: "hours" | "maxFare" } {
  if (draft.windows.some((w) => w.days.length === 0 || !w.from || !w.to || w.from === w.to)) return { error: "hours" };
  let maxFare: MemberPolicy["maxFare"];
  if (draft.maxFare.trim()) {
    const minor = toMinor(draft.maxFare, draft.currency);
    if (Number.isNaN(minor) || minor <= 0) return { error: "maxFare" };
    maxFare = { amountMinor: minor, currency: draft.currency };
  }
  return {
    policy: {
      allowedVehicleClasses: draft.classes.length ? draft.classes : undefined,
      allowedHours: draft.windows.length ? draft.windows.map((w) => ({ days: DAYS.filter((d) => w.days.includes(d)), from: w.from, to: w.to })) : undefined,
      maxFare,
    },
  };
}

function toggle<T>(list: T[], item: T, on: boolean) {
  return on ? (list.includes(item) ? list : [...list, item]) : list.filter((x) => x !== item);
}

export function PolicyEditor({
  value,
  onChange,
  error,
}: {
  value: PolicyDraft;
  onChange: (draft: PolicyDraft) => void;
  error?: "hours" | "maxFare";
}) {
  const t = useTranslations();
  return (
    <div className="flex flex-col gap-4">
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">{t("policy.vehicleClasses")}</legend>
        <p className="text-sm text-muted-foreground">{t("policy.vehicleClassesHint")}</p>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
          {VEHICLE_CLASSES.map((c) => (
            <Label key={c} className="flex items-center gap-2 rounded-lg border px-3 py-2 font-normal leading-normal">
              <Checkbox checked={value.classes.includes(c)} onCheckedChange={(on) => onChange({ ...value, classes: toggle(value.classes, c, on === true) })} />
              {t(`vehicleClass.${c}`)}
            </Label>
          ))}
        </div>
      </fieldset>

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">{t("policy.hours")}</legend>
        <p className="text-sm text-muted-foreground">{t("policy.hoursHint")}</p>
        {value.windows.map((w, i) => {
          const update = (patch: Partial<PolicyDraft["windows"][number]>) =>
            onChange({ ...value, windows: value.windows.map((x, j) => (j === i ? { ...x, ...patch } : x)) });
          return (
            <div key={i} className="flex flex-col gap-2 rounded-lg border p-3">
              <div className="flex flex-wrap gap-1.5">
                {DAYS.map((d) => (
                  <Label key={d} className="flex items-center gap-1.5 rounded-md border px-2 py-1 font-normal leading-normal">
                    <Checkbox checked={w.days.includes(d)} onCheckedChange={(on) => update({ days: toggle(w.days, d, on === true) })} />
                    {t(`day.${d}`)}
                  </Label>
                ))}
              </div>
              <div className="flex flex-wrap items-end gap-2">
                <Field label={t("common.from")} htmlFor={`from-${i}`}>
                  <Input id={`from-${i}`} type="time" value={w.from} onChange={(e) => update({ from: e.target.value })} />
                </Field>
                <Field label={t("common.to")} htmlFor={`to-${i}`}>
                  <Input id={`to-${i}`} type="time" value={w.to} onChange={(e) => update({ to: e.target.value })} />
                </Field>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label={t("policy.removeWindow")}
                  onClick={() => onChange({ ...value, windows: value.windows.filter((_, j) => j !== i) })}
                >
                  <Trash2 />
                </Button>
              </div>
              {w.from && w.to && w.to < w.from && <p className="text-sm text-muted-foreground">{t("policy.overnight")}</p>}
            </div>
          );
        })}
        {error === "hours" && <p className="text-sm text-destructive">{t("policy.hoursInvalid")}</p>}
        <Button
          type="button"
          variant="outline"
          className="self-start"
          onClick={() =>
            onChange({ ...value, windows: [...value.windows, { days: ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"], from: "07:00", to: "19:00" }] })
          }
        >
          <Plus /> {t("policy.addWindow")}
        </Button>
      </fieldset>

      <div className="grid grid-cols-[1fr_140px] gap-2">
        <Field
          label={t("policy.maxFare")}
          htmlFor="maxFare"
          optional
          optionalText={t("common.optional")}
          hint={t("policy.maxFareHint")}
          error={error === "maxFare" ? t("corporates.amountInvalid") : undefined}
        >
          <Input id="maxFare" inputMode="decimal" value={value.maxFare} onChange={(e) => onChange({ ...value, maxFare: e.target.value })} />
        </Field>
        <Field label={t("corporates.currency")} htmlFor="maxFareCurrency">
          <NativeSelect id="maxFareCurrency" value={value.currency} onChange={(e) => onChange({ ...value, currency: e.target.value as Currency })}>
            {CURRENCIES.map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </NativeSelect>
        </Field>
      </div>
    </div>
  );
}

/** Short read-only form for tables. */
export function PolicySummary({ policy }: { policy: MemberPolicy | null | undefined }) {
  const t = useTranslations();
  const classes = policy?.allowedVehicleClasses ?? [];
  const hours = policy?.allowedHours ?? [];
  if (!classes.length && !hours.length && !policy?.maxFare) {
    return <span className="text-muted-foreground">{t("policy.unrestricted")}</span>;
  }
  return (
    <div className="flex flex-wrap gap-1">
      {classes.length > 0 && <Badge variant="outline">{classes.map((c) => t(`vehicleClass.${c}`)).join(", ")}</Badge>}
      {hours.map((w, i) => (
        <Badge key={i} variant="outline">
          {(w.days ?? []).map((d) => t(`dayShort.${d}`)).join(" ")} {(w.from ?? "").slice(0, 5)}–{(w.to ?? "").slice(0, 5)}
        </Badge>
      ))}
      {policy?.maxFare && <Badge variant="outline">≤ {formatMoney(policy.maxFare.amountMinor, policy.maxFare.currency)}</Badge>}
    </div>
  );
}
