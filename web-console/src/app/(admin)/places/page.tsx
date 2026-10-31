import { Suspense } from "react";
import { PlaceList } from "@/features/places/place-list";

export default function PlacesPage() {
  return (
    <Suspense>
      <PlaceList />
    </Suspense>
  );
}
