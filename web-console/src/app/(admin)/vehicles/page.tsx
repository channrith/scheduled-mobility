import { Suspense } from "react";
import { VehicleList } from "@/features/vehicles/vehicle-list";

export default function VehiclesPage() {
  return (
    <Suspense>
      <VehicleList />
    </Suspense>
  );
}
