"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { Merge } from "lucide-react";
import dynamic from "next/dynamic";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";
import { Field } from "@/components/field";
import { NativeSelect } from "@/components/native-select";
import { PageHeader } from "@/components/page/page-header";
import { ErrorState, NotAllowed } from "@/components/page/state";
import { useSession } from "@/components/shell/session";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { ApiError } from "@/lib/api/client";
import { usePlace, useSavePlace } from "@/lib/api/hooks";
import { PLACE_TYPES, type PlaceType, type PlaceView } from "@/lib/api/types";
import { useDates } from "@/lib/format";
import { MergeDialog } from "./merge-dialog";
import { VerifiedMark } from "./place-list";

// MapLibre needs the browser (WebGL, window); never render it on the server.
const PlaceMap = dynamic(() => import("./place-map"), { ssr: false, loading: () => <Skeleton className="h-full min-h-80 w-full" /> });

/** Central Phnom Penh (Independence Monument): where a new pin starts. */
const PHNOM_PENH = { latitude: 11.5564, longitude: 104.9282 };

export function PlaceEditor({ placeId }: { placeId?: string }) {
  const t = useTranslations();
  const session = useSession();
  const place = usePlace(placeId);
  if (!session.can("managePlaces")) return <NotAllowed />;
  if (placeId && place.isPending) return <Skeleton className="h-96 w-full" />;
  if (placeId && place.isError) return <ErrorState error={place.error} onRetry={() => place.refetch()} />;
  // Remount when another version is loaded (after save) so the form starts from the server's values.
  return <PlaceForm key={`${place.data?.id}-${place.data?.version}`} place={place.data} title={placeId ? undefined : t("places.new")} />;
}

const coordinate = (min: number, max: number) =>
  z.string().trim().refine((v) => /^-?\d+(\.\d+)?$/.test(v) && Number(v) >= min && Number(v) <= max);

const schema = z
  .object({
    nameKm: z.string().trim().max(200),
    nameEn: z.string().trim().max(200),
    type: z.enum(PLACE_TYPES as [PlaceType, ...PlaceType[]]),
    latitude: coordinate(-90, 90),
    longitude: coordinate(-180, 180),
    pickupNotesKm: z.string().trim().max(1000),
    pickupNotesEn: z.string().trim().max(1000),
    verified: z.boolean(),
  })
  .refine((v) => v.nameKm !== "" || v.nameEn !== "", { path: ["nameKm"] });

type Values = z.infer<typeof schema>;

function PlaceForm({ place, title }: { place?: PlaceView; title?: string }) {
  const t = useTranslations();
  const dates = useDates();
  const router = useRouter();
  const session = useSession();
  const save = useSavePlace();
  const [merging, setMerging] = useState(false);
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      nameKm: place?.nameKm ?? "",
      nameEn: place?.nameEn ?? "",
      type: place?.type ?? "LANDMARK",
      latitude: (place?.latitude ?? PHNOM_PENH.latitude).toFixed(6),
      longitude: (place?.longitude ?? PHNOM_PENH.longitude).toFixed(6),
      pickupNotesKm: place?.pickupNotesKm ?? "",
      pickupNotesEn: place?.pickupNotesEn ?? "",
      verified: place?.verified ?? false,
    },
  });
  const [lat, lng, verified] = useWatch({ control: form.control, name: ["latitude", "longitude", "verified"] });
  const e = form.formState.errors;
  const merged = place?.status === "MERGED";
  const editable = !merged;

  const movePin = (latitude: number, longitude: number) => {
    form.setValue("latitude", latitude.toFixed(6), { shouldDirty: true, shouldValidate: true });
    form.setValue("longitude", longitude.toFixed(6), { shouldDirty: true, shouldValidate: true });
  };

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={title ?? (place?.nameKm || place?.nameEn || "")}
        description={place && place.nameKm && place.nameEn ? place.nameEn : undefined}
        actions={
          place && (
            <>
              <VerifiedMark verified={place.verified} />
              {session.can("mergePlaces") && !merged && (
                <Button variant="outline" onClick={() => setMerging(true)}>
                  <Merge /> {t("places.merge")}
                </Button>
              )}
            </>
          )
        }
      />
      {merged && (
        <Card className="border-amber-300 bg-amber-50 dark:border-amber-900 dark:bg-amber-950/30" size="sm">
          <CardContent className="text-sm">
            {t("places.mergedNotice")}{" "}
            <Link className="font-medium underline" href={`/places/${place.mergedInto}`}>{t("places.openTarget")}</Link>
          </CardContent>
        </Card>
      )}
      <form
        className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,420px)_1fr]"
        onSubmit={form.handleSubmit((v) =>
          save.mutate(
            {
              id: place?.id,
              version: place?.version,
              nameKm: v.nameKm || undefined,
              nameEn: v.nameEn || undefined,
              type: v.type,
              latitude: Number(v.latitude),
              longitude: Number(v.longitude),
              pickupNotesKm: v.pickupNotesKm || undefined,
              pickupNotesEn: v.pickupNotesEn || undefined,
              verified: v.verified,
            },
            {
              onSuccess: (saved) => {
                toast.success(t("common.saved"));
                if (!place) router.replace(`/places/${saved.id}`);
              },
              onError: (err) => toast.error(err instanceof ApiError && err.detail ? err.detail : t("common.error")),
            },
          ),
        )}
      >
        <fieldset disabled={!editable} className="flex min-w-0 flex-col gap-3">
          <Field label={t("places.nameKm")} htmlFor="nameKm" error={e.nameKm && t("places.nameRequired")}>
            <Input id="nameKm" lang="km" {...form.register("nameKm")} />
          </Field>
          <Field label={t("places.nameEn")} htmlFor="nameEn">
            <Input id="nameEn" lang="en" {...form.register("nameEn")} />
          </Field>
          <Field label={t("places.type")} htmlFor="type">
            <NativeSelect id="type" {...form.register("type")}>
              {PLACE_TYPES.map((p) => (
                <option key={p} value={p}>{t(`placeType.${p}`)}</option>
              ))}
            </NativeSelect>
          </Field>
          <div className="grid grid-cols-2 gap-2">
            <Field label={t("places.latitude")} htmlFor="lat" error={e.latitude && t("places.coordinateInvalid")}>
              <Input id="lat" inputMode="decimal" className="font-mono" {...form.register("latitude")} />
            </Field>
            <Field label={t("places.longitude")} htmlFor="lng" error={e.longitude && t("places.coordinateInvalid")}>
              <Input id="lng" inputMode="decimal" className="font-mono" {...form.register("longitude")} />
            </Field>
          </div>
          <Field label={t("places.pickupNotesKm")} htmlFor="notesKm" optional optionalText={t("common.optional")} hint={t("places.pickupNotesHint")}>
            <Textarea id="notesKm" lang="km" rows={3} {...form.register("pickupNotesKm")} />
          </Field>
          <Field label={t("places.pickupNotesEn")} htmlFor="notesEn" optional optionalText={t("common.optional")}>
            <Textarea id="notesEn" lang="en" rows={3} {...form.register("pickupNotesEn")} />
          </Field>
          <Label className="flex items-start gap-2 rounded-lg border p-3 font-normal leading-normal">
            <Checkbox className="mt-1" checked={verified} onCheckedChange={(on) => form.setValue("verified", on === true, { shouldDirty: true })} />
            <span className="flex flex-col">
              <span className="font-medium">{t("places.markVerified")}</span>
              <span className="text-sm text-muted-foreground">{t("places.markVerifiedHint")}</span>
            </span>
          </Label>
          {place && (
            <p className="text-sm text-muted-foreground">
              {t("places.usageLine", { count: place.usageCount })} · {t("places.updatedAt")}: {dates.dateTime(place.updatedAt)}
            </p>
          )}
          {editable && (
            <div className="flex gap-2">
              <Button type="submit" disabled={save.isPending}>{t("common.save")}</Button>
              <Button type="button" variant="outline" onClick={() => router.push("/places")}>{t("common.cancel")}</Button>
            </div>
          )}
        </fieldset>
        <div className="flex min-h-80 flex-col gap-2 lg:min-h-[560px]">
          <p className="text-sm text-muted-foreground">{t("places.mapHint")}</p>
          <div className="flex-1">
            <PlaceMap latitude={Number(lat)} longitude={Number(lng)} draggable={editable} onMove={movePin} />
          </div>
        </div>
      </form>
      {merging && place && <MergeDialog place={place} onClose={() => setMerging(false)} />}
    </div>
  );
}
