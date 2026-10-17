import { PlaceEditor } from "@/features/places/place-editor";

export default async function PlacePage({ params }: PageProps<"/places/[placeId]">) {
  const { placeId } = await params;
  return <PlaceEditor placeId={placeId} />;
}
