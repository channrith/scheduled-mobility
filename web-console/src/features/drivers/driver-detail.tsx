"use client";

import { AlertTriangle, Pencil, Plus } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { PageHeader } from "@/components/page/page-header";
import { ErrorState, NotAllowed } from "@/components/page/state";
import { useSession } from "@/components/shell/session";
import { DriverStatusBadge } from "@/components/status-badge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { DocumentCard } from "@/features/documents/document-card";
import { DocumentUploadCard } from "@/features/documents/document-upload-card";
import { AssignVehicle } from "@/features/vehicles/assign-vehicle";
import { ApiError } from "@/lib/api/client";
import { useDriver, useDriverAudit, useEndAssignment, useUpdateDriver } from "@/lib/api/hooks";
import { PERSONAL_DOCUMENTS, type DriverDetail as Driver } from "@/lib/api/types";
import { useDates } from "@/lib/format";
import { DriverActions } from "./driver-actions";
import { DriverForm, toDriverInput } from "./driver-form";

export function DriverDetail({ driverId }: { driverId: string }) {
  const t = useTranslations();
  const session = useSession();
  const driver = useDriver(driverId);

  if (!session.can("viewDrivers")) return <NotAllowed />;
  if (driver.isPending) return <Skeleton className="h-96 w-full" />;
  if (driver.isError) return <ErrorState error={driver.error} onRetry={() => driver.refetch()} />;
  const d = driver.data;
  const r = d.readiness;
  const blocked = [...(r.missing ?? []), ...(r.pendingReview ?? []), ...(r.expired ?? [])].length > 0 || !r.hasActiveVehicle;

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={d.fullName}
        description={d.phone}
        actions={
          <>
            <DriverStatusBadge status={d.status} />
            <DriverActions driver={d} />
          </>
        }
      />
      {d.suspension && (
        <Card className="border-destructive/40 bg-destructive/5" size="sm">
          <CardContent className="flex flex-col gap-1 text-sm">
            <span className="font-medium">{t("detail.suspension")}</span>
            <span>{d.suspension.reason}</span>
            <Dates noticeAt={d.suspension.noticeAt} suspendedAt={d.suspension.suspendedAt} />
          </CardContent>
        </Card>
      )}
      {blocked && !["APPROVED", "REJECTED", "SUSPENDED"].includes(d.status) && <Readiness driver={d} />}

      <Tabs defaultValue="documents">
        <TabsList className="flex-wrap">
          <TabsTrigger value="documents">{t("detail.documents")}</TabsTrigger>
          <TabsTrigger value="profile">{t("detail.profile")}</TabsTrigger>
          <TabsTrigger value="vehicles">{t("detail.vehicles")}</TabsTrigger>
          <TabsTrigger value="history">{t("detail.history")}</TabsTrigger>
          {session.can("viewAudit") && <TabsTrigger value="audit">{t("detail.audit")}</TabsTrigger>}
        </TabsList>
        <TabsContent value="documents" className="pt-3"><DocumentsTab driver={d} /></TabsContent>
        <TabsContent value="profile" className="pt-3"><ProfileTab driver={d} /></TabsContent>
        <TabsContent value="vehicles" className="pt-3"><VehiclesTab driver={d} /></TabsContent>
        <TabsContent value="history" className="pt-3"><HistoryTab driver={d} /></TabsContent>
        {session.can("viewAudit") && <TabsContent value="audit" className="pt-3"><AuditTab driverId={d.id} /></TabsContent>}
      </Tabs>
    </div>
  );
}

function Dates({ noticeAt, suspendedAt }: { noticeAt?: string; suspendedAt?: string }) {
  const t = useTranslations("detail");
  const dates = useDates();
  return (
    <span className="text-muted-foreground">
      {t("noticeAt")}: {dates.dateTime(noticeAt)} · {t("suspendedAt")}: {dates.dateTime(suspendedAt)}
    </span>
  );
}

function Readiness({ driver }: { driver: Driver }) {
  const t = useTranslations();
  const r = driver.readiness;
  const list = (items: string[]) => items.map((i) => t(`docType.${i}`)).join(", ");
  return (
    <Card size="sm" className="border-amber-300 bg-amber-50 dark:border-amber-900 dark:bg-amber-950/30">
      <CardContent className="flex flex-col gap-1 text-sm">
        <span className="flex items-center gap-1.5 font-medium"><AlertTriangle className="size-4" /> {t("detail.readiness")}</span>
        {r.missing.length > 0 && <span>{t("detail.missing")}: {list(r.missing)}</span>}
        {r.pendingReview.length > 0 && <span>{t("detail.pendingReview")}: {list(r.pendingReview)}</span>}
        {r.expired.length > 0 && <span>{t("detail.expired")}: {list(r.expired)}</span>}
        {!r.hasActiveVehicle && <span>{t("detail.noVehicle")}</span>}
      </CardContent>
    </Card>
  );
}

function DocumentsTab({ driver }: { driver: Driver }) {
  const t = useTranslations();
  const session = useSession();
  const canUpload = session.can("editDrivers") && driver.status !== "REJECTED";
  const types = [...PERSONAL_DOCUMENTS, ...(driver.vehicle ? (["VEHICLE_REGISTRATION"] as const) : [])];
  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        {driver.documents.map((doc) => (
          <DocumentCard key={doc.id} driverId={driver.id} document={doc} />
        ))}
      </div>
      {canUpload && (
        <section className="flex flex-col gap-3">
          <h2 className="font-medium">{t("document.upload")}</h2>
          {types.map((type) => {
            const current = driver.documents.find((d) => d.type === type && (type !== "VEHICLE_REGISTRATION" || d.vehicleId === driver.vehicle?.id));
            return (
              <DocumentUploadCard
                key={type}
                driverId={driver.id}
                type={type}
                vehicleId={type === "VEHICLE_REGISTRATION" ? driver.vehicle?.id : undefined}
                current={current}
              />
            );
          })}
        </section>
      )}
    </div>
  );
}

function ProfileTab({ driver }: { driver: Driver }) {
  const t = useTranslations();
  const session = useSession();
  const update = useUpdateDriver(driver.id);
  const [editing, setEditing] = useState(false);
  const rows: [string, React.ReactNode][] = [
    [t("register.phone"), driver.phone],
    [t("detail.nationalId"), driver.nationalIdMasked ?? "—"],
    [t("detail.licenseNumber"), driver.licenseNumberMasked ?? "—"],
    [t("register.homeZone"), driver.homeZone ? t(`zone.${driver.homeZone}`) : "—"],
    [t("detail.language"), driver.preferredLanguage ? t(`language.${driver.preferredLanguage}`) : "—"],
    [
      t("detail.bank"),
      driver.bankAccount ? `${driver.bankAccount.bankName ?? ""} · ${driver.bankAccount.accountName ?? ""} · ${driver.bankAccount.accountNumberMasked ?? ""}` : "—",
    ],
  ];
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>{t("detail.profile")}</CardTitle>
        {session.can("editDrivers") && (
          <Button variant="outline" size="sm" onClick={() => setEditing(true)}><Pencil /> {t("common.edit")}</Button>
        )}
      </CardHeader>
      <CardContent>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-[200px_1fr]">
          {rows.map(([label, value]) => (
            <div key={label} className="contents">
              <dt className="text-muted-foreground">{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      </CardContent>
      <Dialog open={editing} onOpenChange={setEditing}>
        <DialogContent className="sm:max-w-3xl">
          <DialogHeader><DialogTitle>{t("detail.editProfile")}</DialogTitle></DialogHeader>
          <DriverForm
            driver={driver}
            submitLabel={t("common.save")}
            pending={update.isPending}
            onCancel={() => setEditing(false)}
            onSubmit={(values) =>
              update.mutate(
                { ...toDriverInput(values), version: Number(driver.version) },
                {
                  onSuccess: () => { toast.success(t("common.saved")); setEditing(false); },
                  onError: (e) => toast.error(e instanceof ApiError && e.detail ? e.detail : t("common.error")),
                },
              )
            }
          />
        </DialogContent>
      </Dialog>
    </Card>
  );
}

function VehiclesTab({ driver }: { driver: Driver }) {
  const t = useTranslations();
  const dates = useDates();
  const session = useSession();
  const end = useEndAssignment(driver.id);
  const [assigning, setAssigning] = useState(false);
  const canManage = session.can("editDrivers") && driver.status !== "REJECTED";
  const [now] = useState(() => Date.now());
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>{t("detail.assignments")}</CardTitle>
        {canManage && <Button size="sm" onClick={() => setAssigning(true)}><Plus /> {t("register.assign")}</Button>}
      </CardHeader>
      <CardContent>
        {driver.vehicleAssignments.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("detail.noVehicle")}</p>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("vehicles.plate")}</TableHead>
                  <TableHead>{t("vehicles.owner")}</TableHead>
                  <TableHead>{t("common.from")}</TableHead>
                  <TableHead>{t("common.to")}</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {driver.vehicleAssignments.map((a) => {
                  const upcoming = new Date(a.validFrom).getTime() > now;
                  const ended = !a.active && !upcoming;
                  return (
                    <TableRow key={a.id}>
                      <TableCell className="leading-normal">
                        <div className="font-mono">{a.vehicle.plateNumber}</div>
                        <div className="text-xs text-muted-foreground">{t(`vehicleClass.${a.vehicle.vehicleClass}`)}</div>
                      </TableCell>
                      <TableCell className="leading-normal">
                        {t(`ownership.${a.vehicle.ownership}`)}
                        {a.vehicle.ownerName && <div className="text-xs text-muted-foreground">{a.vehicle.ownerName} · {a.vehicle.ownerPhone}</div>}
                      </TableCell>
                      <TableCell className="whitespace-nowrap">{dates.dateTime(a.validFrom)}</TableCell>
                      <TableCell className="whitespace-nowrap">{a.validTo ? dates.dateTime(a.validTo) : t("common.openEnded")}</TableCell>
                      <TableCell className="text-right">
                        <Badge variant={a.active ? "default" : "outline"}>
                          {a.active ? t("detail.active") : upcoming ? t("detail.upcoming") : t("detail.ended")}
                        </Badge>
                        {canManage && !ended && (
                          <Button
                            variant="ghost"
                            size="sm"
                            className="ml-2"
                            disabled={end.isPending}
                            onClick={() =>
                              end.mutate(a.id, {
                                onError: (e) => toast.error(e instanceof ApiError && e.detail ? e.detail : t("common.error")),
                              })
                            }
                          >
                            {upcoming ? t("detail.cancelAssignment") : t("detail.endAssignment")}
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
      <Dialog open={assigning} onOpenChange={setAssigning}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader><DialogTitle>{t("register.assign")}</DialogTitle></DialogHeader>
          <AssignVehicle driverId={driver.id} onAssigned={() => setAssigning(false)} />
        </DialogContent>
      </Dialog>
    </Card>
  );
}

function HistoryTab({ driver }: { driver: Driver }) {
  const t = useTranslations();
  const dates = useDates();
  return (
    <ol className="flex flex-col gap-3 border-l pl-4">
      {[...driver.history].reverse().map((h, i) => (
        <li key={i} className="relative">
          <span className="absolute top-2 -left-[21px] size-2.5 rounded-full bg-primary" />
          <div className="flex flex-wrap items-center gap-2">
            <DriverStatusBadge status={h.to} />
            <span className="text-sm text-muted-foreground">{dates.dateTime(h.occurredAt)}</span>
          </div>
          {h.reason && <p className="text-sm">{h.reason}</p>}
          {h.noticeAt && <p className="text-sm text-muted-foreground">{t("detail.noticeAt")}: {dates.dateTime(h.noticeAt)}</p>}
        </li>
      ))}
    </ol>
  );
}

function AuditTab({ driverId }: { driverId: string }) {
  const t = useTranslations();
  const dates = useDates();
  const audit = useDriverAudit(driverId, true);
  if (audit.isPending) return <Skeleton className="h-40 w-full" />;
  if (audit.isError) return <ErrorState error={audit.error} />;
  const label = (action: string) => {
    const key = `audit.${action.replace(/[.-]/g, "_")}`;
    return t.has(key) ? t(key) : action;
  };
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{t("detail.when")}</TableHead>
            <TableHead>{t("detail.action")}</TableHead>
            <TableHead>{t("detail.actor")}</TableHead>
            <TableHead>{t("common.details")}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {audit.data.map((e) => (
            <TableRow key={e.id}>
              <TableCell className="whitespace-nowrap">{dates.dateTime(e.occurredAt)}</TableCell>
              <TableCell>{label(e.action)}</TableCell>
              <TableCell className="leading-normal">
                {e.actorName ?? (e.actorUserId ? e.actorUserId.slice(0, 8) : t("detail.system"))}
                {e.actorRoles.length > 0 && <div className="text-xs text-muted-foreground">{e.actorRoles.join(", ")}</div>}
              </TableCell>
              <TableCell className="max-w-md text-xs text-muted-foreground">
                {Object.entries(e.details ?? {})
                  .filter(([k]) => k !== "driverId")
                  .map(([k, v]) => `${k}: ${Array.isArray(v) ? v.join(", ") : String(v)}`)
                  .join(" · ")}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
