"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useTranslations } from "next-intl";
import { useForm, useWatch } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";
import { Field } from "@/components/field";
import { NativeSelect } from "@/components/native-select";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ApiError } from "@/lib/api/client";
import { useSaveVehicle } from "@/lib/api/hooks";
import { OWNERSHIPS, VEHICLE_CLASSES, type VehicleView } from "@/lib/api/types";

const schema = z
  .object({
    plateNumber: z.string().trim().min(2).max(20),
    vehicleClass: z.enum(VEHICLE_CLASSES as [string, ...string[]]),
    make: z.string().max(50).optional(),
    model: z.string().max(50).optional(),
    color: z.string().max(30).optional(),
    modelYear: z.string().regex(/^(19[89]\d|20\d\d|2100)?$/).optional(),
    seats: z.coerce.number().int().min(1).max(20),
    ownership: z.enum(OWNERSHIPS as [string, ...string[]]),
    ownerName: z.string().max(200).optional(),
    ownerPhone: z.string().optional(),
    status: z.enum(["ACTIVE", "INACTIVE"]),
  })
  .refine((v) => v.ownership !== "THIRD_PARTY" || (!!v.ownerName?.trim() && !!v.ownerPhone?.trim()), {
    path: ["ownerName"],
  });

type Values = z.input<typeof schema>;

/** Create or edit a vehicle. Rented tuk-tuks (THIRD_PARTY) need the owner's name and phone. */
export function VehicleForm({ vehicle, onSaved, onCancel }: { vehicle?: VehicleView; onSaved: (v: VehicleView) => void; onCancel?: () => void }) {
  const t = useTranslations();
  const save = useSaveVehicle();
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      plateNumber: vehicle?.plateNumber ?? "",
      vehicleClass: vehicle?.vehicleClass ?? "TUKTUK_REMORK",
      make: vehicle?.make ?? "",
      model: vehicle?.model ?? "",
      color: vehicle?.color ?? "",
      modelYear: vehicle?.modelYear ? String(vehicle.modelYear) : "",
      seats: vehicle?.seats ?? 4,
      ownership: vehicle?.ownership ?? "DRIVER",
      ownerName: vehicle?.ownerName ?? "",
      ownerPhone: vehicle?.ownerPhone ?? "",
      status: vehicle?.status ?? "ACTIVE",
    },
  });
  const ownership = useWatch({ control: form.control, name: "ownership" });
  const errors = form.formState.errors;

  return (
    <form
      className="grid grid-cols-1 gap-3 sm:grid-cols-2"
      onSubmit={form.handleSubmit((raw) => {
        const v = schema.parse(raw);
        save.mutate(
          {
            id: vehicle?.id,
            version: vehicle?.version,
            status: vehicle ? (v.status as "ACTIVE" | "INACTIVE") : undefined,
            plateNumber: v.plateNumber,
            vehicleClass: v.vehicleClass as VehicleView["vehicleClass"],
            make: v.make || undefined,
            model: v.model || undefined,
            color: v.color || undefined,
            modelYear: v.modelYear ? Number(v.modelYear) : undefined,
            seats: v.seats,
            ownership: v.ownership,
            ownerName: v.ownership === "DRIVER" ? undefined : v.ownerName || undefined,
            ownerPhone: v.ownership === "DRIVER" ? undefined : v.ownerPhone || undefined,
          },
          {
            onSuccess: (saved) => {
              toast.success(t("common.saved"));
              onSaved(saved);
            },
            onError: (e) => toast.error(e instanceof ApiError && e.detail ? e.detail : t("common.error")),
          },
        );
      })}
    >
      <Field label={t("vehicles.plate")} htmlFor="plate" error={errors.plateNumber && t("common.required")}>
        <Input id="plate" className="font-mono uppercase" autoCapitalize="characters" {...form.register("plateNumber")} />
      </Field>
      <Field label={t("vehicles.class")} htmlFor="class">
        <NativeSelect id="class" {...form.register("vehicleClass")}>
          {VEHICLE_CLASSES.map((c) => (
            <option key={c} value={c}>{t(`vehicleClass.${c}`)}</option>
          ))}
        </NativeSelect>
      </Field>
      <Field label={t("vehicles.make")} htmlFor="make" optional optionalText={t("common.optional")}>
        <Input id="make" {...form.register("make")} />
      </Field>
      <Field label={t("vehicles.model")} htmlFor="model" optional optionalText={t("common.optional")}>
        <Input id="model" {...form.register("model")} />
      </Field>
      <Field label={t("vehicles.color")} htmlFor="color" optional optionalText={t("common.optional")}>
        <Input id="color" {...form.register("color")} />
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label={t("vehicles.year")} htmlFor="year" optional optionalText={t("common.optional")}>
          <Input id="year" inputMode="numeric" {...form.register("modelYear")} />
        </Field>
        <Field label={t("vehicles.seats")} htmlFor="seats">
          <Input id="seats" type="number" min={1} max={20} {...form.register("seats")} />
        </Field>
      </div>
      <Field label={t("vehicles.ownership")} htmlFor="ownership" className="sm:col-span-2">
        <NativeSelect id="ownership" {...form.register("ownership")}>
          {OWNERSHIPS.map((o) => (
            <option key={o} value={o}>{t(`ownership.${o}`)}</option>
          ))}
        </NativeSelect>
      </Field>
      {ownership !== "DRIVER" && (
        <>
          <Field
            label={t("vehicles.ownerName")}
            htmlFor="ownerName"
            optional={ownership === "COMPANY"}
            optionalText={t("common.optional")}
            error={errors.ownerName && t("common.required")}
          >
            <Input id="ownerName" {...form.register("ownerName")} />
          </Field>
          <Field label={t("vehicles.ownerPhone")} htmlFor="ownerPhone" optional={ownership === "COMPANY"} optionalText={t("common.optional")}>
            <Input id="ownerPhone" type="tel" inputMode="tel" {...form.register("ownerPhone")} />
          </Field>
        </>
      )}
      {vehicle && (
        <Field label={t("vehicles.status")} htmlFor="status">
          <NativeSelect id="status" {...form.register("status")}>
            <option value="ACTIVE">{t("vehicleStatus.ACTIVE")}</option>
            <option value="INACTIVE">{t("vehicleStatus.INACTIVE")}</option>
          </NativeSelect>
        </Field>
      )}
      <div className="flex gap-2 sm:col-span-2">
        <Button type="submit" disabled={save.isPending}>{t("common.save")}</Button>
        {onCancel && <Button type="button" variant="outline" onClick={onCancel}>{t("common.cancel")}</Button>}
      </div>
    </form>
  );
}
