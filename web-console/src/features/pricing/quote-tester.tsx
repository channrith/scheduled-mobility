"use client";

import { Crosshair, MapPin, Search, TriangleAlert, X } from "lucide-react";
import dynamic from "next/dynamic";
import { useTranslations } from "next-intl";
import { useEffect, useState } from "react";
import { Field } from "@/components/field";
import { NativeSelect } from "@/components/native-select";
import { PageHeader } from "@/components/page/page-header";
import { ErrorState, NotAllowed } from "@/components/page/state";
import { useSession } from "@/components/shell/session";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useCorporates, useCreateQuote, usePlaceSearch, useZones } from "@/lib/api/hooks";
import { VEHICLE_CLASSES, type AccountView, type LatLng, type PlaceHit, type QuoteView, type VehicleClass } from "@/lib/api/types";
import { toLocalInput, useDates } from "@/lib/format";
import { formatMoney } from "@/lib/money";
import { cn } from "@/lib/utils";

const QuoteMap = dynamic(() => import("@/features/maps/quote-map"), { ssr: false, loading: () => <Skeleton className="h-full min-h-96 w-full" /> });

type End = "pickup" | "dropoff";

/** A chosen end: a landmark or a map position. */
type Point = { position: LatLng; place?: PlaceHit };

/** Checks a price before it is given to a client. Every quote it makes is a real (stored) quote. */
export function QuoteTester() {
  const t = useTranslations();
  const session = useSession();
  const zones = useZones();
  const quote = useCreateQuote();
  const [points, setPoints] = useState<Record<End, Point | null>>({ pickup: null, dropoff: null });
  const [picking, setPicking] = useState<End | null>("pickup");
  const [vehicleClass, setVehicleClass] = useState<VehicleClass>("CAR");
  const [pickupAt, setPickupAt] = useState("");
  const [corporate, setCorporate] = useState<AccountView | null>(null);

  if (!session.can("viewPricing")) return <NotAllowed />;

  const choose = (end: End, point: Point | null) => {
    setPoints((p) => ({ ...p, [end]: point }));
    quote.reset();
    if (point && end === "pickup" && !points.dropoff) setPicking("dropoff");
    else if (point) setPicking(null);
  };

  const location = (p: Point) => (p.place ? { placeId: p.place.id } : { latitude: p.position.latitude, longitude: p.position.longitude });
  const ready = points.pickup && points.dropoff;

  return (
    <div className="flex flex-col gap-4">
      <PageHeader title={t("quoteTester.title")} description={t("quoteTester.subtitle")} />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,420px)_1fr]">
        <div className="flex min-w-0 flex-col gap-3">
          <LocationPicker end="pickup" point={points.pickup} picking={picking === "pickup"} onPickOnMap={() => setPicking("pickup")} onChoose={(p) => choose("pickup", p)} />
          <LocationPicker end="dropoff" point={points.dropoff} picking={picking === "dropoff"} onPickOnMap={() => setPicking("dropoff")} onChoose={(p) => choose("dropoff", p)} />
          <div className="grid grid-cols-2 gap-2">
            <Field label={t("rules.vehicleClass")} htmlFor="qt-class">
              <NativeSelect id="qt-class" value={vehicleClass} onChange={(e) => { setVehicleClass(e.target.value as VehicleClass); quote.reset(); }}>
                {VEHICLE_CLASSES.map((c) => (
                  <option key={c} value={c}>{t(`vehicleClass.${c}`)}</option>
                ))}
              </NativeSelect>
            </Field>
            <Field label={t("quoteTester.pickupAt")} htmlFor="qt-time" optional optionalText={t("rules.now")}>
              <Input id="qt-time" type="datetime-local" min={toLocalInput(new Date())} value={pickupAt} onChange={(e) => { setPickupAt(e.target.value); quote.reset(); }} />
            </Field>
          </div>
          <CorporatePicker value={corporate} onChange={(c) => { setCorporate(c); quote.reset(); }} />
          <Button
            disabled={!ready || quote.isPending}
            onClick={() =>
              ready &&
              quote.mutate({
                pickup: location(points.pickup!),
                dropoff: location(points.dropoff!),
                vehicleClass,
                pickupAt: pickupAt ? new Date(pickupAt).toISOString() : undefined,
                corporateId: corporate?.id,
              })
            }
          >
            {t("quoteTester.getQuote")}
          </Button>
          {quote.isError && <ErrorState error={quote.error} />}
        </div>
        <div className="flex min-h-96 flex-col gap-2 lg:min-h-[520px]">
          <p className="text-sm text-muted-foreground">
            {picking ? t(picking === "pickup" ? "quoteTester.clickPickup" : "quoteTester.clickDropoff") : t("quoteTester.mapHint")}
          </p>
          <div className="flex-1">
            <QuoteMap
              zones={zones.data ?? []}
              pickup={points.pickup?.position ?? null}
              dropoff={points.dropoff?.position ?? null}
              picking={picking}
              onPick={(end, position) => choose(end, { position })}
            />
          </div>
        </div>
      </div>
      {quote.data && <QuoteResult quote={quote.data} corporate={corporate} />}
    </div>
  );
}

function LocationPicker({
  end,
  point,
  picking,
  onPickOnMap,
  onChoose,
}: {
  end: End;
  point: Point | null;
  picking: boolean;
  onPickOnMap: () => void;
  onChoose: (point: Point | null) => void;
}) {
  const t = useTranslations();
  const [text, setText] = useState("");
  const [q, setQ] = useState("");
  useEffect(() => {
    const id = setTimeout(() => setQ(text.trim()), 300);
    return () => clearTimeout(id);
  }, [text]);
  const results = usePlaceSearch(q);
  const label = t(end === "pickup" ? "quoteTester.pickup" : "quoteTester.dropoff");

  return (
    <div className={cn("flex flex-col gap-2 rounded-lg border p-3", picking && "border-primary")}>
      <div className="flex items-center justify-between gap-2">
        <span className="flex items-center gap-2 text-sm font-medium">
          <MapPin className={cn("size-4", end === "pickup" ? "text-green-600" : "text-red-600")} /> {label}
        </span>
        <Button type="button" variant={picking ? "default" : "outline"} size="sm" onClick={onPickOnMap}>
          <Crosshair /> {t("quoteTester.onMap")}
        </Button>
      </div>
      {point ? (
        <div className="flex items-start justify-between gap-2 rounded-md bg-muted px-3 py-2 text-sm leading-normal">
          <span className="flex flex-col">
            {point.place ? (
              <>
                <span className="font-medium">{point.place.nameKm || point.place.nameEn}</span>
                {point.place.nameKm && point.place.nameEn && <span className="text-muted-foreground">{point.place.nameEn}</span>}
              </>
            ) : (
              <span className="font-mono text-xs">{point.position.latitude.toFixed(5)}, {point.position.longitude.toFixed(5)}</span>
            )}
          </span>
          <Button type="button" variant="ghost" size="icon-sm" aria-label={t("quoteTester.clear")} onClick={() => onChoose(null)}>
            <X />
          </Button>
        </div>
      ) : (
        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input className="pl-9" aria-label={label} placeholder={t("places.searchPlaceholder")} value={text} onChange={(e) => setText(e.target.value)} />
        </div>
      )}
      {!point && q.length >= 2 && (
        <ul className="flex max-h-56 flex-col gap-1 overflow-y-auto">
          {(results.data ?? []).length === 0 && !results.isFetching && <li className="px-1 text-sm text-muted-foreground">{t("places.empty")}</li>}
          {(results.data ?? []).map((h) => (
            <li key={h.id}>
              <button
                type="button"
                onClick={() => {
                  onChoose({ position: { latitude: h.latitude, longitude: h.longitude }, place: h });
                  setText("");
                }}
                className="flex w-full flex-col items-start rounded-md px-2 py-1.5 text-left text-sm leading-normal hover:bg-muted"
              >
                <span>{h.nameKm || h.nameEn}</span>
                {h.nameKm && h.nameEn && <span className="text-xs text-muted-foreground">{h.nameEn}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function CorporatePicker({ value, onChange }: { value: AccountView | null; onChange: (account: AccountView | null) => void }) {
  const t = useTranslations();
  const [text, setText] = useState("");
  const [q, setQ] = useState("");
  useEffect(() => {
    const id = setTimeout(() => setQ(text.trim()), 300);
    return () => clearTimeout(id);
  }, [text]);
  const results = useCorporates({ q, status: "ACTIVE", page: 0, size: 6 }, q.length >= 2);

  return (
    <Field label={t("quoteTester.corporate")} htmlFor="qt-corporate" optional optionalText={t("common.optional")} hint={t("quoteTester.corporateHint")}>
      {value ? (
        <div className="flex items-center justify-between gap-2 rounded-lg border px-3 py-2 text-sm">
          <span>{value.displayName || value.legalName}</span>
          <Button type="button" variant="ghost" size="icon-sm" aria-label={t("quoteTester.clear")} onClick={() => onChange(null)}>
            <X />
          </Button>
        </div>
      ) : (
        <>
          <Input id="qt-corporate" placeholder={t("corporates.searchPlaceholder")} value={text} onChange={(e) => setText(e.target.value)} />
          {q.length >= 2 && (
            <ul className="flex flex-col gap-1">
              {(results.data?.content ?? []).map((a) => (
                <li key={a.id}>
                  <button
                    type="button"
                    onClick={() => {
                      onChange(a);
                      setText("");
                    }}
                    className="w-full rounded-md px-2 py-1.5 text-left text-sm leading-normal hover:bg-muted"
                  >
                    {a.displayName || a.legalName}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </Field>
  );
}

function QuoteResult({ quote: q, corporate }: { quote: QuoteView; corporate: AccountView | null }) {
  const t = useTranslations();
  const dates = useDates();
  const usd = (amount: number, equivalent: number) => (q.currency === "USD" ? amount : equivalent);
  const khr = (amount: number, equivalent: number) => (q.currency === "KHR" ? amount : equivalent);
  const exact = (currency: string) => (currency === q.currency ? "font-medium" : "text-muted-foreground");

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-2">
        <div className="flex flex-col gap-1">
          <CardTitle>{t("quoteTester.result")}</CardTitle>
          <span className="text-sm text-muted-foreground">
            {t(`vehicleClass.${q.vehicleClass}`)} · {dates.dateTime(q.pickupAt)}
            {corporate && ` · ${corporate.displayName || corporate.legalName}`}
          </span>
        </div>
        <div className="flex flex-wrap gap-2">
          <Badge variant={q.corporateOverride ? "default" : "secondary"}>{q.corporateOverride ? t("quoteTester.companyPrice") : t("quoteTester.cityPrice")}</Badge>
          {q.routingSource === "STRAIGHT_LINE" && (
            <Badge variant="outline" className="h-auto border-amber-400 py-0.5 text-amber-800 dark:text-amber-300">
              <TriangleAlert /> {t("quoteTester.estimate")}
            </Badge>
          )}
        </div>
      </CardHeader>
      <CardContent className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("quoteTester.line")}</TableHead>
                <TableHead className="text-right">USD</TableHead>
                <TableHead className="text-right">KHR</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {q.breakdown.map((l) => (
                <TableRow key={l.type}>
                  <TableCell className="py-2">{t(`quoteLine.${l.type}`)}</TableCell>
                  <TableCell className={cn("text-right tabular-nums", exact("USD"))}>{formatMoney(usd(l.amountMinor, l.equivalentMinor), "USD")}</TableCell>
                  <TableCell className={cn("text-right tabular-nums", exact("KHR"))}>{formatMoney(khr(l.amountMinor, l.equivalentMinor), "KHR")}</TableCell>
                </TableRow>
              ))}
            </TableBody>
            <TableFooter>
              <TableRow>
                <TableCell className="font-semibold">{t("quoteTester.total")}</TableCell>
                <TableCell className="text-right text-base font-semibold tabular-nums">{formatMoney(usd(q.totalMinor, q.equivalent.amountMinor), "USD")}</TableCell>
                <TableCell className="text-right text-base font-semibold tabular-nums">{formatMoney(khr(q.totalMinor, q.equivalent.amountMinor), "KHR")}</TableCell>
              </TableRow>
              {q.serviceFee && (
                <TableRow>
                  <TableCell className="whitespace-normal">{t("quoteTester.serviceFee", { pct: Number(q.serviceFee.pct) })}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatMoney(usd(q.serviceFee.amountMinor, q.serviceFee.equivalentMinor), "USD")}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatMoney(khr(q.serviceFee.amountMinor, q.serviceFee.equivalentMinor), "KHR")}</TableCell>
                </TableRow>
              )}
            </TableFooter>
          </Table>
        </div>
        <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-2 text-sm">
          <dt className="text-muted-foreground">{t("quoteTester.charged")}</dt>
          <dd>{t("quoteTester.chargedIn", { currency: q.currency })}</dd>
          <dt className="text-muted-foreground">{t("quoteTester.distance")}</dt>
          <dd className="tabular-nums">{(q.distanceMeters / 1000).toFixed(1)} {t("rules.km")}</dd>
          <dt className="text-muted-foreground">{t("quoteTester.duration")}</dt>
          <dd className="tabular-nums">{Math.round(q.durationSeconds / 60)} {t("rules.min")}</dd>
          <dt className="text-muted-foreground">{t("quoteTester.eta")}</dt>
          <dd>{dates.dateTime(q.eta)}</dd>
          <dt className="text-muted-foreground">{t("quoteTester.expiresAt")}</dt>
          <dd>{dates.dateTime(q.expiresAt)}</dd>
          <dt className="text-muted-foreground">{t("quoteTester.rate")}</dt>
          <dd className="tabular-nums">1 USD = {Number(q.usdKhrRate).toLocaleString("en-US")} ៛</dd>
          <dt className="text-muted-foreground">{t("pricingSettings.fuel-index")}</dt>
          <dd className="tabular-nums">{Number(q.fuelIndex).toFixed(3)}</dd>
          <dt className="text-muted-foreground">{t("quoteTester.quoteId")}</dt>
          <dd className="font-mono text-xs break-all">{q.id}</dd>
        </dl>
      </CardContent>
    </Card>
  );
}
