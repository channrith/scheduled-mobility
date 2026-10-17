"use client";

import { Pencil, Plus, Trash2 } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { PageHeader } from "@/components/page/page-header";
import { ErrorState, LoadingRows, NotAllowed } from "@/components/page/state";
import { ReasonDialog } from "@/components/reason-dialog";
import { useSession } from "@/components/shell/session";
import {
  AlertDialog, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ApiError } from "@/lib/api/client";
import { useCorporate, useCorporateStatus, useCostCenters, useMembers, useRemoveMember, useSaveCorporate } from "@/lib/api/hooks";
import type { AccountDetail, AccountView, Currency, MemberView } from "@/lib/api/types";
import { useDates } from "@/lib/format";
import { formatMoney } from "@/lib/money";
import { AccountFields, toAccountInput, useAccountForm } from "./account-form";
import { AccountStatusBadge } from "./account-status-badge";
import { CostCentersTab } from "./cost-centers-tab";
import { MemberDialog } from "./member-dialog";
import { PolicySummary } from "./policy-editor";

export function CorporateDetail({ corporateId }: { corporateId: string }) {
  const t = useTranslations();
  const session = useSession();
  const detail = useCorporate(corporateId);
  const status = useCorporateStatus(corporateId);
  const [dialog, setDialog] = useState<"edit" | "suspend" | "reactivate" | null>(null);

  if (!session.can("viewCorporates")) return <NotAllowed />;
  if (detail.isPending) return <Skeleton className="h-96 w-full" />;
  if (detail.isError) return <ErrorState error={detail.error} onRetry={() => detail.refetch()} />;
  const a = detail.data.account;
  const canManage = session.can("manageCorporates");

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={a.displayName || a.legalName}
        description={a.displayName ? a.legalName : undefined}
        actions={
          <>
            <AccountStatusBadge status={a.status} />
            {canManage && (
              <>
                <Button variant="outline" onClick={() => setDialog("edit")}>
                  <Pencil /> {t("common.edit")}
                </Button>
                {a.status === "ACTIVE" ? (
                  <Button variant="outline" className="text-destructive" onClick={() => setDialog("suspend")}>{t("corporates.suspend")}</Button>
                ) : (
                  <Button onClick={() => setDialog("reactivate")}>{t("corporates.reactivate")}</Button>
                )}
              </>
            )}
          </>
        }
      />
      {a.suspension && a.status === "SUSPENDED" && (
        <Card className="border-destructive/40 bg-destructive/5" size="sm">
          <CardContent className="flex flex-col gap-1 text-sm">
            <span className="font-medium">{t("corporates.suspendedNotice")}</span>
            <span>{a.suspension.reason}</span>
          </CardContent>
        </Card>
      )}
      <Tabs defaultValue="overview">
        <TabsList className="flex-wrap">
          <TabsTrigger value="overview">{t("corporates.overview")}</TabsTrigger>
          <TabsTrigger value="members">{t("members.title")}</TabsTrigger>
          <TabsTrigger value="costCenters">{t("costCenters.title")}</TabsTrigger>
          <TabsTrigger value="history">{t("corporates.history")}</TabsTrigger>
        </TabsList>
        <TabsContent value="overview" className="pt-3"><Overview detail={detail.data} /></TabsContent>
        <TabsContent value="members" className="pt-3"><MembersTab account={a} canManage={canManage} /></TabsContent>
        <TabsContent value="costCenters" className="pt-3"><CostCentersTab corporateId={a.id} canManage={canManage} /></TabsContent>
        <TabsContent value="history" className="pt-3"><History detail={detail.data} /></TabsContent>
      </Tabs>

      <Dialog open={dialog === "edit"} onOpenChange={(open) => !open && setDialog(null)}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{t("corporates.editTitle")}</DialogTitle>
          </DialogHeader>
          {dialog === "edit" && <EditAccount account={a} onDone={() => setDialog(null)} />}
        </DialogContent>
      </Dialog>
      {(dialog === "suspend" || dialog === "reactivate") && (
        <ReasonDialog
          open
          onOpenChange={(open) => !open && setDialog(null)}
          title={t(dialog === "suspend" ? "corporates.suspendTitle" : "corporates.reactivateTitle")}
          description={dialog === "suspend" ? t("corporates.suspendHint") : undefined}
          confirmLabel={t(dialog === "suspend" ? "corporates.suspend" : "corporates.reactivate")}
          destructive={dialog === "suspend"}
          pending={status.isPending}
          onConfirm={({ reason }) =>
            status.mutate(
              { action: dialog, reason },
              {
                onSuccess: () => {
                  setDialog(null);
                  toast.success(t("common.saved"));
                },
                onError: (e) => toast.error(e instanceof ApiError && e.detail ? e.detail : t("common.error")),
              },
            )
          }
        />
      )}
    </div>
  );
}

function EditAccount({ account, onDone }: { account: AccountView; onDone: () => void }) {
  const t = useTranslations();
  const form = useAccountForm(account);
  const save = useSaveCorporate();
  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={form.handleSubmit((values) =>
        save.mutate(
          { id: account.id, version: account.version, ...toAccountInput(values) },
          {
            onSuccess: () => {
              toast.success(t("common.saved"));
              onDone();
            },
            onError: (e) => toast.error(e instanceof ApiError && e.detail ? e.detail : t("common.error")),
          },
        ),
      )}
    >
      <AccountFields form={form} />
      <div className="flex gap-2">
        <Button type="submit" disabled={save.isPending}>{t("common.save")}</Button>
        <Button type="button" variant="outline" onClick={onDone}>{t("common.cancel")}</Button>
      </div>
    </form>
  );
}

function Overview({ detail }: { detail: AccountDetail }) {
  const t = useTranslations();
  const dates = useDates();
  const a = detail.account;
  const rows: [string, React.ReactNode][] = [
    [t("corporates.legalName"), a.legalName],
    [t("corporates.displayName"), a.displayName || "—"],
    [t("corporates.taxId"), <span key="tax" className="font-mono">{a.taxId || "—"}</span>],
    [t("corporates.paymentTerms"), t("corporates.days", { count: a.paymentTermsDays })],
    [t("corporates.creditLimitShort"), formatMoney(a.creditLimitMinor, a.currency)],
    [t("corporates.serviceFee"), `${Number(a.serviceFeePct).toFixed(2)} %`],
    [t("corporates.activeMembers"), detail.activeMembers],
    [t("corporates.createdAt"), dates.dateTime(a.createdAt)],
  ];
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("corporates.account")}</CardTitle>
      </CardHeader>
      <CardContent>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-[max-content_1fr]">
          {rows.map(([label, value]) => (
            <div key={label} className="contents">
              <dt className="text-muted-foreground">{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      </CardContent>
    </Card>
  );
}

function MembersTab({ account, canManage }: { account: AccountView; canManage: boolean }) {
  const t = useTranslations();
  const members = useMembers(account.id);
  const costCenters = useCostCenters(account.id);
  const remove = useRemoveMember(account.id);
  const [editing, setEditing] = useState<MemberView | "new" | null>(null);
  const [removing, setRemoving] = useState<MemberView | null>(null);

  if (members.isPending || costCenters.isPending) return <LoadingRows rows={4} />;
  if (members.isError) return <ErrorState error={members.error} onRetry={() => members.refetch()} />;
  if (costCenters.isError) return <ErrorState error={costCenters.error} onRetry={() => costCenters.refetch()} />;

  return (
    <div className="flex flex-col gap-3">
      {canManage && (
        <Button className="self-start" disabled={account.status !== "ACTIVE"} onClick={() => setEditing("new")}>
          <Plus /> {t("members.add")}
        </Button>
      )}
      <div className="overflow-x-auto rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("members.person")}</TableHead>
              <TableHead>{t("members.role")}</TableHead>
              <TableHead>{t("members.costCenter")}</TableHead>
              <TableHead>{t("policy.title")}</TableHead>
              {canManage && <TableHead><span className="sr-only">{t("common.actions")}</span></TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {members.data.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} className="py-10 text-center text-muted-foreground">{t("members.empty")}</TableCell>
              </TableRow>
            ) : (
              members.data.map((m) => (
                <TableRow key={m.id}>
                  <TableCell className="py-2.5 leading-normal">
                    <div className="flex flex-col">
                      <span className="font-medium">{m.fullName || "—"}</span>
                      <span className="text-xs text-muted-foreground">
                        {m.phone}
                        {m.employeeRef && ` · ${m.employeeRef}`}
                      </span>
                    </div>
                  </TableCell>
                  <TableCell>
                    <Badge variant={m.role === "CORPORATE_ADMIN" ? "default" : "secondary"}>{t(`memberRole.${m.role}`)}</Badge>
                  </TableCell>
                  <TableCell>{m.costCenter ? `${m.costCenter.code} · ${m.costCenter.name}` : "—"}</TableCell>
                  <TableCell className="max-w-80 whitespace-normal"><PolicySummary policy={m.policy} /></TableCell>
                  {canManage && (
                    <TableCell className="text-right whitespace-nowrap">
                      <Button variant="ghost" size="icon-sm" aria-label={t("common.edit")} onClick={() => setEditing(m)}>
                        <Pencil />
                      </Button>
                      <Button variant="ghost" size="icon-sm" className="text-destructive" aria-label={t("members.remove")} onClick={() => setRemoving(m)}>
                        <Trash2 />
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
        <MemberDialog
          corporateId={account.id}
          member={editing === "new" ? undefined : editing}
          costCenters={costCenters.data}
          currency={account.currency as Currency}
          onClose={() => setEditing(null)}
        />
      )}
      <AlertDialog open={removing !== null} onOpenChange={(open) => !open && setRemoving(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("members.removeTitle", { name: removing?.fullName || removing?.phone || "" })}</AlertDialogTitle>
            <AlertDialogDescription>{t("members.removeHint")}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
            <Button
              variant="destructive"
              disabled={remove.isPending}
              onClick={() =>
                removing &&
                remove.mutate(removing.id, {
                  onSuccess: () => {
                    setRemoving(null);
                    toast.success(t("common.saved"));
                  },
                  onError: (e) => toast.error(e instanceof ApiError && e.detail ? e.detail : t("common.error")),
                })
              }
            >
              {t("members.remove")}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function History({ detail }: { detail: AccountDetail }) {
  const t = useTranslations();
  const dates = useDates();
  if (detail.history.length === 0) return <p className="text-sm text-muted-foreground">{t("corporates.noHistory")}</p>;
  return (
    <ol className="flex flex-col gap-3 border-l pl-4">
      {[...detail.history].reverse().map((h, i) => (
        <li key={i} className="relative">
          <span className="absolute top-2 -left-[21px] size-2.5 rounded-full bg-primary" />
          <div className="flex flex-wrap items-center gap-2">
            <AccountStatusBadge status={h.to} />
            <span className="text-sm text-muted-foreground">{dates.dateTime(h.occurredAt)}</span>
          </div>
          {h.reason && <p className="text-sm">{h.reason}</p>}
        </li>
      ))}
    </ol>
  );
}
