"use client";

import { useTranslations } from "next-intl";
import { NativeSelect } from "@/components/native-select";
import { PageHeader } from "@/components/page/page-header";
import { ErrorState, LoadingRows, NotAllowed } from "@/components/page/state";
import { useSession } from "@/components/shell/session";
import { usePricingCities } from "@/lib/api/hooks";
import { useUrlState } from "@/lib/use-url-state";
import { PriceLines } from "./price-lines";

export function PricingRules() {
  const t = useTranslations();
  const session = useSession();
  const url = useUrlState();
  const cities = usePricingCities();
  if (!session.can("viewPricing")) return <NotAllowed />;
  if (cities.isPending) return <LoadingRows />;
  if (cities.isError) return <ErrorState error={cities.error} onRetry={() => cities.refetch()} />;
  const city = url.get("city") ?? cities.data[0];
  const cityName = (c: string) => (t.has(`city.${c}`) ? t(`city.${c}`) : c);

  return (
    <div className="flex flex-col gap-3">
      <PageHeader
        title={t("rules.title")}
        description={t("rules.subtitle")}
        actions={
          cities.data.length > 1 ? (
            <NativeSelect aria-label={t("rules.city")} value={city} onChange={(e) => url.set({ city: e.target.value })}>
              {cities.data.map((c) => (
                <option key={c} value={c}>{cityName(c)}</option>
              ))}
            </NativeSelect>
          ) : (
            <span className="text-sm text-muted-foreground">{cityName(city)}</span>
          )
        }
      />
      <PriceLines city={city} />
    </div>
  );
}
