import { Suspense } from "react";
import { CorporateList } from "@/features/corporates/corporate-list";

export default function CorporatesPage() {
  return (
    <Suspense>
      <CorporateList />
    </Suspense>
  );
}
