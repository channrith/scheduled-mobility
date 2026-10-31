import { Suspense } from "react";
import { PricingRules } from "@/features/pricing/pricing-rules";

export default function PricingRulesPage() {
  return (
    <Suspense>
      <PricingRules />
    </Suspense>
  );
}
