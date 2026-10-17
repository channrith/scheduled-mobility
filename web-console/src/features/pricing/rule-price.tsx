"use client";

import { useTranslations } from "next-intl";
import type { RuleView } from "@/lib/api/types";
import { formatMoney } from "@/lib/money";

/** "$2.00 + $0.50/km + $0.10/min · min. $5.00", with the fuel factor and maximum surge underneath. */
export function RulePrice({ rule }: { rule: RuleView }) {
  const t = useTranslations("rules");
  const m = (minor: number) => formatMoney(minor, rule.currency);
  return (
    <div className="flex flex-col leading-normal">
      <span className="tabular-nums">
        {m(rule.baseMinor)} + {m(rule.perKmMinor)}/{t("km")} + {m(rule.perMinMinor)}/{t("min")}
      </span>
      <span className="text-xs text-muted-foreground">
        {t("minFareShort")} {m(rule.minFareMinor)} · {t("fuelShort")} ×{Number(rule.fuelIndexFactor)} · {t("surgeShort")} ≤ ×{Number(rule.maxSurge)}
      </span>
    </div>
  );
}
