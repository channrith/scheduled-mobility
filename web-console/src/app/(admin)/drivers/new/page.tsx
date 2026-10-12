import { Suspense } from "react";
import { RegisterWizard } from "@/features/drivers/register-wizard";

export default function RegisterDriverPage() {
  return (
    <Suspense>
      <RegisterWizard />
    </Suspense>
  );
}
