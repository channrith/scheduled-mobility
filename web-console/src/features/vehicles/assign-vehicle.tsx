"use client";

import { Search } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ApiError } from "@/lib/api/client";
import { useAssignVehicle, useVehicles } from "@/lib/api/hooks";
import type { VehicleView } from "@/lib/api/types";
import { toLocalInput } from "@/lib/format";
import { cn } from "@/lib/utils";
import { VehicleForm } from "./vehicle-form";

/** Pick an existing vehicle or register a new one, then assign it for a period. */
export function AssignVehicle({ driverId, onAssigned }: { driverId: string; onAssigned?: () => void }) {
  const t = useTranslations();
  const [q, setQ] = useState("");
  const [selected, setSelected] = useState<VehicleView | null>(null);
  const [validFrom, setValidFrom] = useState(toLocalInput(new Date()));
  const [validTo, setValidTo] = useState("");
  const vehicles = useVehicles({ q: q || undefined, status: "ACTIVE", page: 0, size: 8 });
  const assign = useAssignVehicle(driverId);

  const submit = () =>
    selected &&
    assign.mutate(
      {
        vehicleId: selected.id,
        validFrom: validFrom ? new Date(validFrom).toISOString() : undefined,
        validTo: validTo ? new Date(validTo).toISOString() : undefined,
      },
      {
        onSuccess: () => {
          toast.success(t("register.assigned"));
          onAssigned?.();
        },
        onError: (e) => toast.error(e instanceof ApiError && e.detail ? e.detail : t("common.error")),
      },
    );

  return (
    <div className="flex flex-col gap-4">
      <Tabs defaultValue="existing">
        <TabsList>
          <TabsTrigger value="existing">{t("register.existingVehicle")}</TabsTrigger>
          <TabsTrigger value="new">{t("register.newVehicle")}</TabsTrigger>
        </TabsList>
        <TabsContent value="existing" className="flex flex-col gap-2 pt-2">
          <div className="relative">
            <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input className="pl-9" placeholder={t("register.searchPlate")} value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <ul className="flex flex-col divide-y rounded-lg border">
            {(vehicles.data?.content ?? []).map(({ vehicle, assignedDriverName }) => (
              <li key={vehicle!.id}>
                <button
                  type="button"
                  onClick={() => setSelected(vehicle as VehicleView)}
                  className={cn(
                    "flex w-full items-center justify-between gap-3 px-3 py-2.5 text-left hover:bg-muted",
                    selected?.id === vehicle!.id && "bg-muted",
                  )}
                >
                  <span className="font-mono">{vehicle!.plateNumber}</span>
                  <span className="text-sm text-muted-foreground">
                    {t(`vehicleClass.${vehicle!.vehicleClass}`)}
                    {assignedDriverName ? ` · ${assignedDriverName}` : ""}
                  </span>
                </button>
              </li>
            ))}
            {vehicles.data?.content.length === 0 && <li className="px-3 py-4 text-sm text-muted-foreground">{t("vehicles.empty")}</li>}
          </ul>
        </TabsContent>
        <TabsContent value="new" className="pt-2">
          <VehicleForm onSaved={(v) => setSelected(v)} />
        </TabsContent>
      </Tabs>
      {selected && (
        <div className="flex flex-col gap-3 rounded-lg border bg-muted/40 p-3">
          <p className="font-medium">
            <span className="font-mono">{selected.plateNumber}</span> · {t(`vehicleClass.${selected.vehicleClass}`)}
          </p>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label={t("common.from")} htmlFor="validFrom">
              <Input id="validFrom" type="datetime-local" value={validFrom} onChange={(e) => setValidFrom(e.target.value)} />
            </Field>
            <Field label={t("common.to")} htmlFor="validTo" optional optionalText={t("common.openEnded")}>
              <Input id="validTo" type="datetime-local" value={validTo} min={validFrom} onChange={(e) => setValidTo(e.target.value)} />
            </Field>
          </div>
          <Button className="self-start" onClick={submit} disabled={assign.isPending}>
            {t("register.assign")}
          </Button>
        </div>
      )}
    </div>
  );
}
