"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

type Problem = { code?: string; detail?: string };

export function LoginForm() {
  const t = useTranslations("login");
  const next = useSearchParams().get("next");
  const [phone, setPhone] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const phoneForm = useForm<{ phone: string }>({
    resolver: zodResolver(z.object({ phone: z.string().trim().min(6) })),
    defaultValues: { phone: "" },
  });
  const codeForm = useForm<{ code: string }>({
    resolver: zodResolver(z.object({ code: z.string().trim().regex(/^\d{4,8}$/) })),
    defaultValues: { code: "" },
  });

  const post = async (url: string, body: unknown) => {
    setPending(true);
    setError(null);
    try {
      const response = await fetch(url, {
        method: "POST",
        headers: { "content-type": "application/json", "idempotency-key": crypto.randomUUID() },
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        const problem = (await response.json().catch(() => ({}))) as Problem;
        setError(problem.code === "console.not-staff" ? t("notStaff") : (problem.detail ?? `HTTP ${response.status}`));
        return false;
      }
      return true;
    } finally {
      setPending(false);
    }
  };

  return (
    <Card className="w-full max-w-sm">
      <CardHeader>
        <CardTitle className="text-xl">{t("title")}</CardTitle>
        <CardDescription>{phone ? t("codeSent", { phone }) : t("subtitle")}</CardDescription>
      </CardHeader>
      <CardContent>
        {!phone ? (
          <form
            className="flex flex-col gap-4"
            onSubmit={phoneForm.handleSubmit(async (v) => {
              if (await post("/api/auth/otp/request", { phone: v.phone })) setPhone(v.phone);
            })}
          >
            <Field label={t("phone")} htmlFor="phone" error={error ?? undefined}>
              <Input id="phone" type="tel" inputMode="tel" autoComplete="tel" placeholder={t("phonePlaceholder")} autoFocus {...phoneForm.register("phone")} />
            </Field>
            <Button type="submit" size="lg" disabled={pending}>
              {t("sendCode")}
            </Button>
          </form>
        ) : (
          <form
            className="flex flex-col gap-4"
            onSubmit={codeForm.handleSubmit(async (v) => {
              if (await post("/api/auth/otp/verify", { phone, code: v.code })) {
                // Full reload: the app starts fresh with the new session cookies.
                 
                window.location.assign(next && next.startsWith("/") && !next.startsWith("//") ? next : "/drivers");
              }
            })}
          >
            <Field label={t("code")} htmlFor="code" error={error ?? undefined}>
              <Input id="code" inputMode="numeric" autoComplete="one-time-code" className="text-center text-lg tracking-[0.5em]" autoFocus {...codeForm.register("code")} />
            </Field>
            <Button type="submit" size="lg" disabled={pending}>
              {t("verify")}
            </Button>
            <Button type="button" variant="ghost" onClick={() => { setPhone(null); setError(null); codeForm.reset(); }}>
              {t("changePhone")}
            </Button>
          </form>
        )}
      </CardContent>
    </Card>
  );
}
