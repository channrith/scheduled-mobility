"use client";

import { Check } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { toast } from "sonner";
import { PageHeader } from "@/components/page/page-header";
import { NotAllowed } from "@/components/page/state";
import { useSession } from "@/components/shell/session";
import { DriverStatusBadge } from "@/components/status-badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { DocumentUploadCard } from "@/features/documents/document-upload-card";
import { AssignVehicle } from "@/features/vehicles/assign-vehicle";
import { ApiError } from "@/lib/api/client";
import { useDriver, useDriverAction, useRegisterDriver, useUpdateDriver } from "@/lib/api/hooks";
import { PERSONAL_DOCUMENTS, type DriverDetail } from "@/lib/api/types";
import { useUrlState } from "@/lib/use-url-state";
import { cn } from "@/lib/utils";
import { DriverForm, toDriverInput } from "./driver-form";

const STEPS = ["personal", "vehicle", "documents", "review"] as const;
type Step = (typeof STEPS)[number];

/**
 * In-person registration. Each step saves immediately and the driver id is kept in the URL, so an
 * interrupted session (lost connection, closed tablet) continues where it stopped.
 */
export function RegisterWizard() {
  const t = useTranslations();
  const session = useSession();
  const url = useUrlState();
  const driverId = url.get("driverId");
  const step = (STEPS.includes(url.get("step") as Step) ? url.get("step") : "personal") as Step;
  const driver = useDriver(driverId);

  if (!session.can("registerDrivers")) return <NotAllowed />;
  const go = (next: Step, extra: Record<string, string> = {}) => url.set({ step: next, ...extra });

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader
        title={t("register.title")}
        description={driver.data ? t("register.resume", { name: driver.data.fullName }) : t("register.subtitle")}
      />
      <ol className="mb-6 grid grid-cols-2 gap-2 sm:grid-cols-4">
        {STEPS.map((s, i) => {
          const done = STEPS.indexOf(step) > i;
          const reachable = s === "personal" || !!driverId;
          return (
            <li key={s}>
              <button
                type="button"
                disabled={!reachable}
                onClick={() => go(s)}
                className={cn(
                  "flex w-full items-center gap-2 rounded-lg border px-3 py-2 text-left text-sm disabled:opacity-50",
                  step === s && "border-primary bg-primary/5 font-medium",
                )}
              >
                <span
                  className={cn(
                    "flex size-6 shrink-0 items-center justify-center rounded-full border text-xs",
                    (done || step === s) && "border-primary bg-primary text-primary-foreground",
                  )}
                >
                  {done ? <Check className="size-3.5" /> : i + 1}
                </span>
                {t(`register.steps.${s}`)}
              </button>
            </li>
          );
        })}
      </ol>

      {driverId && driver.isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : step === "personal" ? (
        <PersonalStep driver={driver.data} onDone={(id) => go("vehicle", { driverId: id })} />
      ) : !driver.data ? null : step === "vehicle" ? (
        <Card>
          <CardContent className="flex flex-col gap-4">
            {driver.data.vehicle && (
              <p className="rounded-lg border bg-emerald-50 p-3 text-sm dark:bg-emerald-950/30">
                {t("detail.currentVehicle")}: <span className="font-mono">{driver.data.vehicle.plateNumber}</span> ·{" "}
                {t(`vehicleClass.${driver.data.vehicle.vehicleClass}`)}
              </p>
            )}
            <AssignVehicle driverId={driver.data.id} onAssigned={() => go("documents")} />
            <div className="flex gap-2">
              <Button variant="outline" onClick={() => go("personal")}>{t("common.back")}</Button>
              <Button variant="ghost" onClick={() => go("documents")}>
                {driver.data.vehicle ? t("common.next") : t("register.skipVehicle")}
              </Button>
            </div>
          </CardContent>
        </Card>
      ) : step === "documents" ? (
        <DocumentsStep driver={driver.data} onBack={() => go("vehicle")} onNext={() => go("review")} />
      ) : (
        <ReviewStep driver={driver.data} onBack={() => go("documents")} />
      )}
    </div>
  );
}

function PersonalStep({ driver, onDone }: { driver?: DriverDetail; onDone: (driverId: string) => void }) {
  const t = useTranslations();
  const register = useRegisterDriver();
  const update = useUpdateDriver(driver?.id ?? "");
  const onError = (e: unknown) => toast.error(e instanceof ApiError && e.detail ? e.detail : t("common.error"));
  return (
    <Card>
      <CardContent>
        <DriverForm
          key={driver?.id ?? "new"}
          driver={driver}
          submitLabel={t("common.next")}
          pending={register.isPending || update.isPending}
          onSubmit={(values) => {
            if (driver) {
              update.mutate({ ...toDriverInput(values), version: Number(driver.version) }, { onSuccess: () => onDone(driver.id), onError });
            } else {
              register.mutate(
                { phone: values.phone.trim(), ...toDriverInput(values) },
                {
                  onSuccess: (created) => {
                    toast.success(t("register.created"));
                    onDone(created.id);
                  },
                  onError,
                },
              );
            }
          }}
        />
      </CardContent>
    </Card>
  );
}

function DocumentsStep({ driver, onBack, onNext }: { driver: DriverDetail; onBack: () => void; onNext: () => void }) {
  const t = useTranslations();
  const current = (type: string, vehicleId?: string) =>
    driver.documents.find((d) => d.type === type && (vehicleId ? d.vehicleId === vehicleId : true));
  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted-foreground">{t("register.documentsHint")}</p>
      {PERSONAL_DOCUMENTS.map((type) => (
        <DocumentUploadCard key={type} driverId={driver.id} type={type} current={current(type)} />
      ))}
      {driver.vehicle && (
        <DocumentUploadCard
          driverId={driver.id}
          type="VEHICLE_REGISTRATION"
          vehicleId={driver.vehicle.id}
          current={current("VEHICLE_REGISTRATION", driver.vehicle.id)}
        />
      )}
      <div className="flex gap-2">
        <Button variant="outline" onClick={onBack}>{t("common.back")}</Button>
        <Button onClick={onNext}>{t("common.next")}</Button>
      </div>
    </div>
  );
}

function ReviewStep({ driver, onBack }: { driver: DriverDetail; onBack: () => void }) {
  const t = useTranslations();
  const router = useRouter();
  const action = useDriverAction(driver.id);
  const missing = (driver.readiness.missing ?? []).filter((m) => PERSONAL_DOCUMENTS.includes(m as never));
  return (
    <Card>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center gap-3">
          <h2 className="text-lg font-semibold">{driver.fullName}</h2>
          <DriverStatusBadge status={driver.status} />
        </div>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
          <dt className="text-muted-foreground">{t("register.phone")}</dt>
          <dd>{driver.phone}</dd>
          <dt className="text-muted-foreground">{t("register.homeZone")}</dt>
          <dd>{driver.homeZone ? t(`zone.${driver.homeZone}`) : "—"}</dd>
          <dt className="text-muted-foreground">{t("detail.currentVehicle")}</dt>
          <dd>{driver.vehicle ? `${driver.vehicle.plateNumber} · ${t(`vehicleClass.${driver.vehicle.vehicleClass}`)}` : t("detail.noVehicle")}</dd>
          <dt className="text-muted-foreground">{t("detail.documents")}</dt>
          <dd>{driver.documents.map((d) => t(`docType.${d.type}`)).join(", ") || "—"}</dd>
        </dl>
        <p className={cn("rounded-lg p-3 text-sm", missing.length ? "bg-amber-50 dark:bg-amber-950/30" : "bg-emerald-50 dark:bg-emerald-950/30")}>
          {missing.length ? t("register.missing", { list: missing.map((m) => t(`docType.${m}`)).join(", ") }) : t("register.ready")}
        </p>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={onBack}>{t("common.back")}</Button>
          {driver.status === "PENDING" ? (
            <Button
              size="lg"
              disabled={missing.length > 0 || action.isPending}
              onClick={() =>
                action.mutate(
                  { action: "submit-documents" },
                  {
                    onSuccess: () => {
                      toast.success(t("register.submitted"));
                      router.push(`/drivers/${driver.id}`);
                    },
                    onError: (e) => toast.error(e instanceof ApiError && e.detail ? e.detail : t("common.error")),
                  },
                )
              }
            >
              {t("register.submit")}
            </Button>
          ) : (
            <Link href={`/drivers/${driver.id}`} className={buttonVariants({ size: "lg" })}>
              {t("review.openDriver")}
            </Link>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
