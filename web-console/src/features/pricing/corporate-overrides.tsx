"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { NativeSelect } from "@/components/native-select";
import { ErrorState, LoadingRows } from "@/components/page/state";
import { usePricingCities } from "@/lib/api/hooks";
import { PriceLines } from "./price-lines";

/** The company's own prices; where it has none, the city price applies. */
export function CorporateOverrides({ corporateId }: { corporateId: string }) {
  const t = useTranslations();
  const cities = usePricingCities();
  const [city, setCity] = useState<string | null>(null);
  if (cities.isPending) return <LoadingRows rows={4} />;
  if (cities.isError) return <ErrorState error={cities.error} onRetry={() => cities.refetch()} />;
  const current = city ?? cities.data[0];
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">{t("rules.overridesHint")}</p>
        {cities.data.length > 1 && (
          <NativeSelect aria-label={t("rules.city")} value={current} onChange={(e) => setCity(e.target.value)}>
            {cities.data.map((c) => (
              <option key={c} value={c}>{t.has(`city.${c}`) ? t(`city.${c}`) : c}</option>
            ))}
          </NativeSelect>
        )}
      </div>
      <PriceLines city={current} corporateId={corporateId} />
    </div>
  );
}
