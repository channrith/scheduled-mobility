"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { Field } from "@/components/field";
import { NativeSelect } from "@/components/native-select";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import { ApiError } from "@/lib/api/client";
import { useSaveMember } from "@/lib/api/hooks";
import { MEMBER_ROLES, type CostCenterView, type Currency, type MemberRole, type MemberView } from "@/lib/api/types";
import { fromDraft, PolicyEditor, toDraft } from "./policy-editor";

/** Add a member (by phone; an existing user is reused) or edit role, cost centre, employee ref and policy. */
export function MemberDialog({
  corporateId,
  member,
  costCenters,
  currency,
  onClose,
}: {
  corporateId: string;
  member?: MemberView;
  costCenters: CostCenterView[];
  currency: Currency;
  onClose: () => void;
}) {
  const t = useTranslations();
  const save = useSaveMember(corporateId);
  const [phone, setPhone] = useState("");
  const [fullName, setFullName] = useState("");
  const [role, setRole] = useState<MemberRole>(member?.role ?? "RIDER");
  const [costCenterId, setCostCenterId] = useState(member?.costCenter?.id ?? "");
  const [employeeRef, setEmployeeRef] = useState(member?.employeeRef ?? "");
  const [draft, setDraft] = useState(() => toDraft(member?.policy, currency));
  const [errors, setErrors] = useState<{ phone?: boolean; fullName?: boolean; policy?: "hours" | "maxFare" }>({});
  // Inactive cost centres stay selectable only for members already in one.
  const selectable = costCenters.filter((c) => c.active || c.id === member?.costCenter?.id);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const result = fromDraft(draft);
    const next = {
      phone: !member && phone.trim().length < 6,
      fullName: !member && !fullName.trim(),
      policy: "error" in result ? result.error : undefined,
    };
    setErrors(next);
    if (next.phone || next.fullName || !("policy" in result)) return;
    save.mutate(
      {
        id: member?.id,
        version: member?.version,
        phone: member ? undefined : phone.trim(),
        fullName: member ? undefined : fullName.trim(),
        role,
        costCenterId: costCenterId || undefined,
        employeeRef: employeeRef.trim() || undefined,
        policy: result.policy,
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

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-2xl">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{member ? t("members.editTitle", { name: member.fullName || member.phone }) : t("members.add")}</DialogTitle>
          </DialogHeader>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {!member && (
              <>
                <Field label={t("register.phone")} htmlFor="memberPhone" hint={t("members.phoneHint")} error={errors.phone ? t("common.required") : undefined}>
                  <Input id="memberPhone" type="tel" inputMode="tel" autoFocus value={phone} onChange={(e) => setPhone(e.target.value)} />
                </Field>
                <Field label={t("register.fullName")} htmlFor="memberName" error={errors.fullName ? t("common.required") : undefined}>
                  <Input id="memberName" value={fullName} onChange={(e) => setFullName(e.target.value)} />
                </Field>
              </>
            )}
            <Field label={t("members.role")} htmlFor="memberRole" hint={t(`memberRoleHint.${role}`)}>
              <NativeSelect id="memberRole" value={role} onChange={(e) => setRole(e.target.value as MemberRole)}>
                {MEMBER_ROLES.map((r) => (
                  <option key={r} value={r}>{t(`memberRole.${r}`)}</option>
                ))}
              </NativeSelect>
            </Field>
            <Field label={t("members.costCenter")} htmlFor="memberCostCenter" optional optionalText={t("common.optional")}>
              <NativeSelect id="memberCostCenter" value={costCenterId} onChange={(e) => setCostCenterId(e.target.value)}>
                <option value="">—</option>
                {selectable.map((c) => (
                  <option key={c.id} value={c.id}>{c.code} · {c.name}</option>
                ))}
              </NativeSelect>
            </Field>
            <Field label={t("members.employeeRef")} htmlFor="employeeRef" optional optionalText={t("common.optional")}>
              <Input id="employeeRef" value={employeeRef} onChange={(e) => setEmployeeRef(e.target.value)} />
            </Field>
          </div>
          <Separator />
          <div>
            <h3 className="font-medium">{t("policy.title")}</h3>
            <p className="mb-3 text-sm text-muted-foreground">{t("policy.hint")}</p>
            <PolicyEditor value={draft} onChange={setDraft} error={errors.policy} />
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t("common.cancel")}</Button>
            <Button type="submit" disabled={save.isPending}>{t("common.save")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
