"use client";

import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { Field } from "@/components/field";
import { PageHeader } from "@/components/page/page-header";
import { NotAllowed } from "@/components/page/state";
import { useSession } from "@/components/shell/session";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ApiError } from "@/lib/api/client";
import { useAddCorporateAdmin, useSaveCorporate } from "@/lib/api/hooks";
import { AccountFields, toAccountInput, useAccountForm } from "./account-form";

/** New corporate client, optionally with its first company admin (who can then add their own colleagues). */
export function CorporateNew() {
  const t = useTranslations();
  const router = useRouter();
  const session = useSession();
  const form = useAccountForm();
  const save = useSaveCorporate();
  const addAdmin = useAddCorporateAdmin();
  const [admin, setAdmin] = useState({ phone: "", fullName: "" });
  const [adminError, setAdminError] = useState<string | null>(null);

  if (!session.can("manageCorporates")) return <NotAllowed />;

  const errorText = (e: unknown) => (e instanceof ApiError && e.detail ? e.detail : t("common.error"));
  const pending = save.isPending || addAdmin.isPending;

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-4">
      <PageHeader title={t("corporates.new")} description={t("corporates.newHint")} />
      <form
        className="flex flex-col gap-4"
        onSubmit={form.handleSubmit(async (values) => {
          const wantsAdmin = admin.phone.trim() !== "" || admin.fullName.trim() !== "";
          if (wantsAdmin && (admin.phone.trim().length < 6 || admin.fullName.trim() === "")) {
            setAdminError(t("corporates.adminIncomplete"));
            return;
          }
          setAdminError(null);
          let created;
          try {
            created = await save.mutateAsync(toAccountInput(values));
          } catch (e) {
            toast.error(errorText(e));
            return;
          }
          if (wantsAdmin) {
            try {
              await addAdmin.mutateAsync({ corporateId: created.id, phone: admin.phone.trim(), fullName: admin.fullName.trim() });
            } catch (e) {
              // The account exists; the admin can be added again from its page.
              toast.error(`${t("corporates.adminFailed")} ${errorText(e)}`);
              router.push(`/corporates/${created.id}`);
              return;
            }
          }
          toast.success(t("common.saved"));
          router.push(`/corporates/${created.id}`);
        })}
      >
        <Card>
          <CardHeader>
            <CardTitle>{t("corporates.account")}</CardTitle>
          </CardHeader>
          <CardContent>
            <AccountFields form={form} />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>{t("corporates.firstAdmin")}</CardTitle>
            <CardDescription>{t("corporates.firstAdminHint")}</CardDescription>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label={t("register.phone")} htmlFor="adminPhone" optional optionalText={t("common.optional")} error={adminError ?? undefined}>
              <Input id="adminPhone" type="tel" inputMode="tel" value={admin.phone} onChange={(e) => setAdmin({ ...admin, phone: e.target.value })} />
            </Field>
            <Field label={t("register.fullName")} htmlFor="adminName" optional optionalText={t("common.optional")}>
              <Input id="adminName" value={admin.fullName} onChange={(e) => setAdmin({ ...admin, fullName: e.target.value })} />
            </Field>
          </CardContent>
        </Card>
        <div className="flex gap-2">
          <Button type="submit" disabled={pending}>{t("corporates.create")}</Button>
          <Button type="button" variant="outline" onClick={() => router.push("/corporates")}>{t("common.cancel")}</Button>
        </div>
      </form>
    </div>
  );
}
