import { Suspense } from "react";
import { DriverList } from "@/features/drivers/driver-list";

export default function DriversPage() {
  return (
    <Suspense>
      <DriverList />
    </Suspense>
  );
}
