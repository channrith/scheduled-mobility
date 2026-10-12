"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { Field } from "@/components/field";
import { NativeSelect } from "@/components/native-select";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { HOME_ZONES, type DriverDetail } from "@/lib/api/types";

export type DriverFormValues = {
  phone: string;
  fullName: string;
  nationalId: string;
  licenseNumber: string;
  homeZone: string;
  preferredLanguage: "KM" | "EN";
  bankName: string;
  bankAccountName: string;
  bankAccountNumber: string;
};

const idNumber = z.string().trim().refine((v) => v === "" || /^[A-Za-z0-9 .\-/]{5,30}$/.test(v));

/**
 * Personal details. Creating needs the phone; editing cannot change it (it is the driver's login).
 * National ID, licence and bank account are shown masked by the API, so when editing they start empty and
 * stay unchanged unless re-entered.
 */
export function DriverForm({
  driver,
  submitLabel,
  pending,
  onSubmit,
  onCancel,
}: {
  driver?: DriverDetail;
  submitLabel: string;
  pending?: boolean;
  onSubmit: (values: DriverFormValues) => void;
  onCancel?: () => void;
}) {
  const t = useTranslations();
  const editing = !!driver;
  const schema = z.object({
    phone: editing ? z.string() : z.string().trim().min(6, t("common.required")),
    fullName: z.string().trim().min(2, t("common.required")).max(200),
    nationalId: idNumber,
    licenseNumber: idNumber,
    homeZone: z.string(),
    preferredLanguage: z.enum(["KM", "EN"]),
    bankName: z.string().max(100),
    bankAccountName: z.string().max(200),
    bankAccountNumber: z.string().trim().refine((v) => v === "" || /^[0-9 \-]{4,40}$/.test(v)),
  });
  const form = useForm<DriverFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      phone: driver?.phone ?? "",
      fullName: driver?.fullName ?? "",
      nationalId: "",
      licenseNumber: "",
      homeZone: driver?.homeZone ?? "",
      preferredLanguage: (driver?.preferredLanguage as "KM" | "EN" | undefined) ?? "KM",
      bankName: driver?.bankAccount?.bankName ?? "",
      bankAccountName: driver?.bankAccount?.accountName ?? "",
      bankAccountNumber: "",
    },
  });
  const e = form.formState.errors;
  const keep = editing ? t("detail.keepUnchanged") : undefined;

  return (
    <form className="grid grid-cols-1 gap-4 md:grid-cols-2" onSubmit={form.handleSubmit(onSubmit)}>
      <Field label={t("register.phone")} htmlFor="phone" hint={editing ? undefined : t("register.phoneHint")} error={e.phone?.message}>
        <Input id="phone" type="tel" inputMode="tel" disabled={editing} autoFocus={!editing} {...form.register("phone")} />
      </Field>
      <Field label={t("register.fullName")} htmlFor="fullName" error={e.fullName?.message}>
        <Input id="fullName" autoComplete="off" {...form.register("fullName")} />
      </Field>
      <Field
        label={t("register.nationalId")}
        htmlFor="nationalId"
        optional
        optionalText={t("common.optional")}
        hint={editing ? `${driver?.nationalIdMasked ?? "—"} · ${keep}` : undefined}
        error={e.nationalId && t("common.error")}
      >
        <Input id="nationalId" inputMode="numeric" autoComplete="off" {...form.register("nationalId")} />
      </Field>
      <Field
        label={t("register.licenseNumber")}
        htmlFor="licenseNumber"
        optional
        optionalText={t("common.optional")}
        hint={editing ? `${driver?.licenseNumberMasked ?? "—"} · ${keep}` : undefined}
        error={e.licenseNumber && t("common.error")}
      >
        <Input id="licenseNumber" autoComplete="off" {...form.register("licenseNumber")} />
      </Field>
      <Field label={t("register.homeZone")} htmlFor="homeZone" optional optionalText={t("common.optional")}>
        <NativeSelect id="homeZone" {...form.register("homeZone")}>
          <option value="">—</option>
          {HOME_ZONES.map((z) => (
            <option key={z} value={z}>{t(`zone.${z}`)}</option>
          ))}
        </NativeSelect>
      </Field>
      <Field label={t("register.preferredLanguage")} htmlFor="language">
        <NativeSelect id="language" {...form.register("preferredLanguage")}>
          <option value="KM">{t("language.KM")}</option>
          <option value="EN">{t("language.EN")}</option>
        </NativeSelect>
      </Field>
      <fieldset className="grid grid-cols-1 gap-4 rounded-lg border p-3 md:col-span-2 md:grid-cols-3">
        <legend className="px-1 text-sm font-medium">
          {t("register.bank")} <span className="font-normal text-muted-foreground">({t("common.optional")})</span>
        </legend>
        <Field label={t("register.bankName")} htmlFor="bankName">
          <Input id="bankName" placeholder="ABA, ACLEDA, Wing…" {...form.register("bankName")} />
        </Field>
        <Field label={t("register.bankAccountName")} htmlFor="bankAccountName">
          <Input id="bankAccountName" {...form.register("bankAccountName")} />
        </Field>
        <Field
          label={t("register.bankAccountNumber")}
          htmlFor="bankAccountNumber"
          hint={editing ? `${driver?.bankAccount?.accountNumberMasked ?? "—"} · ${keep}` : undefined}
          error={e.bankAccountNumber && t("common.error")}
        >
          <Input id="bankAccountNumber" inputMode="numeric" autoComplete="off" {...form.register("bankAccountNumber")} />
        </Field>
      </fieldset>
      <div className="flex gap-2 md:col-span-2">
        <Button type="submit" size="lg" disabled={pending}>{submitLabel}</Button>
        {onCancel && <Button type="button" size="lg" variant="outline" onClick={onCancel}>{t("common.cancel")}</Button>}
      </div>
    </form>
  );
}

/** Only fields that were filled in are sent; empty masked fields mean "unchanged". */
export function toDriverInput(v: DriverFormValues) {
  const opt = (s: string) => (s.trim() === "" ? undefined : s.trim());
  return {
    fullName: v.fullName.trim(),
    nationalId: opt(v.nationalId),
    licenseNumber: opt(v.licenseNumber),
    homeZone: opt(v.homeZone),
    preferredLanguage: v.preferredLanguage,
    bankName: opt(v.bankName),
    bankAccountName: opt(v.bankAccountName),
    bankAccountNumber: opt(v.bankAccountNumber),
  };
}
