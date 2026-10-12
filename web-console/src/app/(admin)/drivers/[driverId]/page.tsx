import { DriverDetail } from "@/features/drivers/driver-detail";

export default async function DriverPage({ params }: PageProps<"/drivers/[driverId]">) {
  const { driverId } = await params;
  return <DriverDetail driverId={driverId} />;
}
