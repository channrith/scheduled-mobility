"use client";

import { Pencil, Plus } from "lucide-react";
import dynamic from "next/dynamic";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { Field } from "@/components/field";
import { NativeSelect } from "@/components/native-select";
import { PageHeader } from "@/components/page/page-header";
import { ErrorState, LoadingRows, NotAllowed } from "@/components/page/state";
import { useSession } from "@/components/shell/session";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError } from "@/lib/api/client";
import { usePricingCities, useSaveZone, useZones } from "@/lib/api/hooks";
import { CURRENCIES, ZONE_TYPES, type Currency, type LatLng, type ZoneType, type ZoneView } from "@/lib/api/types";
import { useDates } from "@/lib/format";
import { formatMoney, fromMinor, toMinor } from "@/lib/money";
import { ZONE_COLORS, type ZoneEditing } from "@/features/maps/zone-map";

const ZoneMap = dynamic(() => import("@/features/maps/zone-map"), { ssr: false, loading: () => <Skeleton className="h-full min-h-96 w-full" /> });

type Draft = {
  id?: string;
  version?: number;
  city: string;
  type: ZoneType;
  nameKm: string;
  nameEn: string;
  surcharge: string;
  surchargeCurrency: Currency;
  active: boolean;
  polygon: LatLng[] | null;
};

function draftOf(zone: ZoneView): Draft {
  const currency = (zone.surchargeCurrency as Currency | undefined) ?? "USD";
  return {
    id: zone.id,
    version: zone.version,
    city: zone.city,
    type: zone.type,
    nameKm: zone.nameKm ?? "",
    nameEn: zone.nameEn ?? "",
    surcharge: zone.surchargeMinor !== undefined && zone.surchargeMinor !== null ? fromMinor(zone.surchargeMinor, currency) : "",
    surchargeCurrency: currency,
    active: zone.active,
    polygon: zone.polygon,
  };
}

export function ZonesPage() {
  const t = useTranslations();
  const dates = useDates();
  const session = useSession();
  const canManage = session.can("managePricing");
  const cities = usePricingCities();
  const zones = useZones();
  const save = useSaveZone();
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const [draft, setDraft] = useState<Draft | null>(null);
  const [editKey, setEditKey] = useState(0);

  if (!session.can("viewPricing")) return <NotAllowed />;
  if (zones.isPending || cities.isPending) return <LoadingRows />;
  if (zones.isError) return <ErrorState error={zones.error} onRetry={() => zones.refetch()} />;
  if (cities.isError) return <ErrorState error={cities.error} onRetry={() => cities.refetch()} />;

  const selected = zones.data.find((z) => z.id === selectedId);
  const cityName = (c: string) => (t.has(`city.${c}`) ? t(`city.${c}`) : c);
  const name = (z: { nameKm?: string | null; nameEn?: string | null }) => z.nameKm || z.nameEn || "—";
  const editing: ZoneEditing = draft ? { key: `${draft.id ?? "new"}-${editKey}`, polygon: draft.id ? draft.polygon : null } : null;
  const set = (patch: Partial<Draft>) => setDraft((d) => (d ? { ...d, ...patch } : d));

  const startNew = () => {
    setSelectedId(undefined);
    setEditKey((k) => k + 1);
    setDraft({ city: cities.data[0], type: "CITY", nameKm: "", nameEn: "", surcharge: "", surchargeCurrency: "USD", active: true, polygon: null });
  };
  const startEdit = (zone: ZoneView) => {
    setEditKey((k) => k + 1);
    setDraft(draftOf(zone));
  };

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!draft) return;
    if (!draft.polygon || draft.polygon.length < 3) {
      toast.error(t("zones.drawFirst"));
      return;
    }
    if (!draft.nameKm.trim() && !draft.nameEn.trim()) {
      toast.error(t("places.nameRequired"));
      return;
    }
    const surcharge = draft.type === "AIRPORT" ? toMinor(draft.surcharge.trim() || "x", draft.surchargeCurrency) : undefined;
    if (surcharge !== undefined && Number.isNaN(surcharge)) {
      toast.error(t("corporates.amountInvalid"));
      return;
    }
    save.mutate(
      {
        id: draft.id,
        version: draft.version,
        city: draft.city,
        type: draft.type,
        nameKm: draft.nameKm.trim() || undefined,
        nameEn: draft.nameEn.trim() || undefined,
        polygon: draft.polygon,
        surchargeMinor: surcharge,
        surchargeCurrency: draft.type === "AIRPORT" ? draft.surchargeCurrency : undefined,
        active: draft.active,
      },
      {
        onSuccess: (zone) => {
          toast.success(t("common.saved"));
          setDraft(null);
          setSelectedId(zone.id);
        },
        onError: (err) => toast.error(err instanceof ApiError && err.detail ? err.detail : t("common.error")),
      },
    );
  };

  return (
    <div className="flex flex-col gap-3">
      <PageHeader
        title={t("zones.title")}
        description={t("zones.subtitle")}
        actions={
          canManage && !draft && (
            <Button onClick={startNew}>
              <Plus /> {t("zones.new")}
            </Button>
          )
        }
      />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,380px)_1fr]">
        <div className="flex min-w-0 flex-col gap-3">
          {draft ? (
            <Card>
              <CardHeader>
                <CardTitle>{draft.id ? t("zones.editTitle") : t("zones.new")}</CardTitle>
              </CardHeader>
              <CardContent>
                <form className="flex flex-col gap-3" onSubmit={submit}>
                  <p className="rounded-lg bg-muted p-3 text-sm leading-normal">
                    {draft.polygon ? t("zones.editHint") : t("zones.drawHint")}
                  </p>
                  <div className="grid grid-cols-2 gap-2">
                    <Field label={t("zones.type")} htmlFor="zone-type">
                      <NativeSelect id="zone-type" value={draft.type} onChange={(e) => set({ type: e.target.value as ZoneType })}>
                        {ZONE_TYPES.map((z) => (
                          <option key={z} value={z}>{t(`zoneType.${z}`)}</option>
                        ))}
                      </NativeSelect>
                    </Field>
                    <Field label={t("rules.city")} htmlFor="zone-city">
                      <NativeSelect id="zone-city" value={draft.city} onChange={(e) => set({ city: e.target.value })}>
                        {cities.data.map((c) => (
                          <option key={c} value={c}>{cityName(c)}</option>
                        ))}
                      </NativeSelect>
                    </Field>
                  </div>
                  <p className="text-sm text-muted-foreground">{t(`zoneTypeHint.${draft.type}`)}</p>
                  <Field label={t("places.nameKm")} htmlFor="zone-km">
                    <Input id="zone-km" lang="km" value={draft.nameKm} onChange={(e) => set({ nameKm: e.target.value })} />
                  </Field>
                  <Field label={t("places.nameEn")} htmlFor="zone-en">
                    <Input id="zone-en" lang="en" value={draft.nameEn} onChange={(e) => set({ nameEn: e.target.value })} />
                  </Field>
                  {draft.type === "AIRPORT" && (
                    <div className="grid grid-cols-[1fr_120px] gap-2">
                      <Field label={t("zones.surcharge")} htmlFor="zone-surcharge" hint={t("zones.surchargeHint")}>
                        <Input id="zone-surcharge" inputMode="decimal" value={draft.surcharge} onChange={(e) => set({ surcharge: e.target.value })} />
                      </Field>
                      <Field label={t("corporates.currency")} htmlFor="zone-currency">
                        <NativeSelect id="zone-currency" value={draft.surchargeCurrency} onChange={(e) => set({ surchargeCurrency: e.target.value as Currency })}>
                          {CURRENCIES.map((c) => (
                            <option key={c} value={c}>{c}</option>
                          ))}
                        </NativeSelect>
                      </Field>
                    </div>
                  )}
                  <Label className="flex items-center gap-2 font-normal">
                    <Checkbox checked={draft.active} onCheckedChange={(on) => set({ active: on === true })} />
                    {t("zones.active")}
                  </Label>
                  <div className="flex flex-wrap gap-2">
                    <Button type="submit" disabled={save.isPending}>{t("common.save")}</Button>
                    {draft.polygon && (
                      <Button type="button" variant="outline" onClick={() => { set({ polygon: null }); setEditKey((k) => k + 1); }}>
                        {t("zones.redraw")}
                      </Button>
                    )}
                    <Button type="button" variant="ghost" onClick={() => setDraft(null)}>{t("common.cancel")}</Button>
                  </div>
                </form>
              </CardContent>
            </Card>
          ) : selected ? (
            <Card size="sm">
              <CardContent className="flex flex-col gap-2 text-sm">
                <div className="flex items-start justify-between gap-2">
                  <div className="flex flex-col">
                    <span className="font-medium">{name(selected)}</span>
                    {selected.nameKm && selected.nameEn && <span className="text-muted-foreground">{selected.nameEn}</span>}
                  </div>
                  {canManage && (
                    <Button variant="outline" size="sm" onClick={() => startEdit(selected)}>
                      <Pencil /> {t("common.edit")}
                    </Button>
                  )}
                </div>
                <span>{t(`zoneType.${selected.type}`)} · {cityName(selected.city)}</span>
                {selected.type === "AIRPORT" && <span>{t("zones.surcharge")}: {formatMoney(selected.surchargeMinor, selected.surchargeCurrency)}</span>}
                <span className="text-muted-foreground">
                  {t("zones.points", { count: selected.polygon.length })} · {t("places.updatedAt")}: {dates.dateTime(selected.updatedAt)}
                </span>
              </CardContent>
            </Card>
          ) : null}
          <ul className="flex flex-col gap-1" aria-label={t("zones.title")}>
            {zones.data.length === 0 && <li className="rounded-lg border p-4 text-sm text-muted-foreground">{t("zones.empty")}</li>}
            {zones.data.map((z) => (
              <li key={z.id}>
                <button
                  type="button"
                  disabled={!!draft}
                  onClick={() => setSelectedId(z.id)}
                  className={`flex w-full items-center gap-3 rounded-lg border px-3 py-2 text-left text-sm leading-normal hover:bg-muted disabled:opacity-60 ${z.id === selectedId ? "border-primary bg-primary/5" : ""}`}
                >
                  <span className="size-3 shrink-0 rounded-full" style={{ backgroundColor: ZONE_COLORS[z.type] }} />
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="truncate font-medium">{name(z)}</span>
                    <span className="text-xs text-muted-foreground">
                      {t(`zoneType.${z.type}`)}
                      {z.type === "AIRPORT" && ` · ${formatMoney(z.surchargeMinor, z.surchargeCurrency)}`}
                    </span>
                  </span>
                  {!z.active && <Badge variant="outline">{t("zones.inactive")}</Badge>}
                </button>
              </li>
            ))}
          </ul>
        </div>
        <div className="flex min-h-96 flex-col gap-2 lg:min-h-[640px]">
          <div className="flex flex-wrap gap-3 text-xs text-muted-foreground">
            {ZONE_TYPES.map((z) => (
              <span key={z} className="inline-flex items-center gap-1.5">
                <span className="size-2.5 rounded-full" style={{ backgroundColor: ZONE_COLORS[z] }} /> {t(`zoneType.${z}`)}
              </span>
            ))}
          </div>
          <div className="flex-1">
            <ZoneMap
              zones={zones.data}
              selectedId={draft?.id ?? selectedId}
              editing={editing}
              onSelect={setSelectedId}
              onPolygonChange={(polygon) => set({ polygon })}
            />
          </div>
        </div>
      </div>
    </div>
  );
}
