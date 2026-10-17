"use client";

import { Pencil, Plus } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { Field } from "@/components/field";
import { ErrorState, LoadingRows } from "@/components/page/state";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ApiError } from "@/lib/api/client";
import { useCostCenters, useSaveCostCenter } from "@/lib/api/hooks";
import type { CostCenterView } from "@/lib/api/types";

export function CostCentersTab({ corporateId, canManage }: { corporateId: string; canManage: boolean }) {
  const t = useTranslations();
  const costCenters = useCostCenters(corporateId);
  const [editing, setEditing] = useState<CostCenterView | "new" | null>(null);

  if (costCenters.isPending) return <LoadingRows rows={3} />;
  if (costCenters.isError) return <ErrorState error={costCenters.error} onRetry={() => costCenters.refetch()} />;

  return (
    <div className="flex flex-col gap-3">
      {canManage && (
        <Button className="self-start" onClick={() => setEditing("new")}>
          <Plus /> {t("costCenters.add")}
        </Button>
      )}
      <div className="overflow-x-auto rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("costCenters.code")}</TableHead>
              <TableHead>{t("costCenters.name")}</TableHead>
              <TableHead>{t("costCenters.status")}</TableHead>
              {canManage && <TableHead><span className="sr-only">{t("common.actions")}</span></TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {costCenters.data.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4} className="py-10 text-center text-muted-foreground">{t("costCenters.empty")}</TableCell>
              </TableRow>
            ) : (
              costCenters.data.map((c) => (
                <TableRow key={c.id}>
                  <TableCell className="py-2.5 font-mono">{c.code}</TableCell>
                  <TableCell>{c.name}</TableCell>
                  <TableCell>
                    <Badge variant={c.active ? "secondary" : "outline"}>{t(c.active ? "costCenters.active" : "costCenters.inactive")}</Badge>
                  </TableCell>
                  {canManage && (
                    <TableCell className="text-right">
                      <Button variant="ghost" size="icon-sm" aria-label={t("common.edit")} onClick={() => setEditing(c)}>
                        <Pencil />
                      </Button>
                    </TableCell>
                  )}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>
      {editing !== null && (
        <CostCenterDialog corporateId={corporateId} costCenter={editing === "new" ? undefined : editing} onClose={() => setEditing(null)} />
      )}
    </div>
  );
}

function CostCenterDialog({ corporateId, costCenter, onClose }: { corporateId: string; costCenter?: CostCenterView; onClose: () => void }) {
  const t = useTranslations();
  const save = useSaveCostCenter(corporateId);
  const [code, setCode] = useState(costCenter?.code ?? "");
  const [name, setName] = useState(costCenter?.name ?? "");
  const [active, setActive] = useState(costCenter?.active ?? true);
  const [touched, setTouched] = useState(false);
  const invalid = { code: !costCenter && !code.trim(), name: !name.trim() };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-md">
        <form
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            setTouched(true);
            if (invalid.code || invalid.name) return;
            save.mutate(
              costCenter
                ? { id: costCenter.id, version: costCenter.version, name: name.trim(), active }
                : { code: code.trim(), name: name.trim() },
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
            <DialogTitle>{costCenter ? t("costCenters.editTitle") : t("costCenters.add")}</DialogTitle>
          </DialogHeader>
          <Field label={t("costCenters.code")} htmlFor="ccCode" hint={costCenter ? undefined : t("costCenters.codeHint")} error={touched && invalid.code ? t("common.required") : undefined}>
            <Input id="ccCode" className="font-mono uppercase" maxLength={30} disabled={!!costCenter} autoFocus={!costCenter} value={code} onChange={(e) => setCode(e.target.value)} />
          </Field>
          <Field label={t("costCenters.name")} htmlFor="ccName" error={touched && invalid.name ? t("common.required") : undefined}>
            <Input id="ccName" maxLength={100} autoFocus={!!costCenter} value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          {costCenter && (
            <Label className="flex items-center gap-2 font-normal">
              <Checkbox checked={active} onCheckedChange={(on) => setActive(on === true)} />
              {t("costCenters.active")}
            </Label>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t("common.cancel")}</Button>
            <Button type="submit" disabled={save.isPending}>{t("common.save")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
