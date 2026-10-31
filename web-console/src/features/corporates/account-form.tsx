"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useTranslations } from "next-intl";
import { useForm, useWatch } from "react-hook-form";
import { z } from "zod";
import { Field } from "@/components/field";
import { NativeSelect } from "@/components/native-select";
import { Input } from "@/components/ui/input";
import type { AccountInput } from "@/lib/api/hooks";
import { CURRENCIES, type AccountView } from "@/lib/api/types";
import { fromMinor, toMinor } from "@/lib/money";

export type AccountFormValues = z.infer<ReturnType<typeof accountSchema>>;

export function accountSchema(t: ReturnType<typeof useTranslations>) {
  return z
    .object({
      legalName: z.string().trim().min(1, t("common.required")).max(200),
      displayName: z.string().trim().max(100),
      taxId: z.string().trim().max(30),
      paymentTermsDays: z
        .string()
        .trim()
        .regex(/^\d{1,3}$/, t("corporates.termsRange"))
        .refine((v) => Number(v) <= 120, t("corporates.termsRange")),
      currency: z.enum(CURRENCIES),
      creditLimit: z.string().trim(),
      serviceFeePct: z.string().trim().regex(/^\d{1,3}(\.\d{1,2})?$/, t("corporates.feeRange")),
    })
    .refine((v) => !Number.isNaN(toMinor(v.creditLimit || "0", v.currency)), {
      path: ["creditLimit"],
      message: t("corporates.amountInvalid"),
    })
    .refine((v) => Number(v.serviceFeePct) <= 100, { path: ["serviceFeePct"], message: t("corporates.feeRange") });
}

export function accountDefaults(account?: AccountView): AccountFormValues {
  const currency = (account?.currency as "USD" | "KHR" | undefined) ?? "USD";
  return {
    legalName: account?.legalName ?? "",
    displayName: account?.displayName ?? "",
    taxId: account?.taxId ?? "",
    paymentTermsDays: String(account?.paymentTermsDays ?? 30),
    currency,
    creditLimit: account ? fromMinor(account.creditLimitMinor, currency) : "0",
    serviceFeePct: account ? String(account.serviceFeePct) : "8.00",
  };
}

export function toAccountInput(values: AccountFormValues): AccountInput {
  return {
    legalName: values.legalName.trim(),
    displayName: values.displayName.trim() || undefined,
    taxId: values.taxId.trim() || undefined,
    paymentTermsDays: Number(values.paymentTermsDays),
    currency: values.currency,
    creditLimitMinor: toMinor(values.creditLimit || "0", values.currency),
    serviceFeePct: Number(values.serviceFeePct),
  };
}

export function useAccountForm(account?: AccountView) {
  const t = useTranslations();
  return useForm<AccountFormValues>({ resolver: zodResolver(accountSchema(t)), defaultValues: accountDefaults(account) });
}

/** The account's fields; the parent owns the <form> so "new" can add the first admin below them. */
export function AccountFields({ form }: { form: ReturnType<typeof useAccountForm> }) {
  const t = useTranslations();
  const e = form.formState.errors;
  const currency = useWatch({ control: form.control, name: "currency" });
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
      <Field label={t("corporates.legalName")} htmlFor="legalName" error={e.legalName?.message} className="sm:col-span-2">
        <Input id="legalName" autoFocus {...form.register("legalName")} />
      </Field>
      <Field label={t("corporates.displayName")} htmlFor="displayName" optional optionalText={t("common.optional")}>
        <Input id="displayName" {...form.register("displayName")} />
      </Field>
      <Field label={t("corporates.taxId")} htmlFor="taxId" optional optionalText={t("common.optional")} hint={t("corporates.taxIdHint")}>
        <Input id="taxId" className="font-mono uppercase" {...form.register("taxId")} />
      </Field>
      <Field label={t("corporates.paymentTerms")} htmlFor="terms" hint={t("corporates.paymentTermsHint")} error={e.paymentTermsDays?.message}>
        <Input id="terms" type="number" inputMode="numeric" min={0} max={120} {...form.register("paymentTermsDays")} />
      </Field>
      <Field label={t("corporates.serviceFee")} htmlFor="fee" hint={t("corporates.serviceFeeHint")} error={e.serviceFeePct?.message}>
        <Input id="fee" inputMode="decimal" {...form.register("serviceFeePct")} />
      </Field>
      <Field label={t("corporates.currency")} htmlFor="currency">
        <NativeSelect id="currency" {...form.register("currency")}>
          {CURRENCIES.map((c) => (
            <option key={c} value={c}>{t(`currency.${c}`)}</option>
          ))}
        </NativeSelect>
      </Field>
      <Field label={t("corporates.creditLimit", { currency })} htmlFor="credit" hint={t("corporates.creditLimitHint")} error={e.creditLimit?.message}>
        <Input id="credit" inputMode="decimal" {...form.register("creditLimit")} />
      </Field>
    </div>
  );
}
