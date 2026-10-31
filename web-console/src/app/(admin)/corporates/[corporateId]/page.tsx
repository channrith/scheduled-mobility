import { CorporateDetail } from "@/features/corporates/corporate-detail";

export default async function CorporatePage({ params }: PageProps<"/corporates/[corporateId]">) {
  const { corporateId } = await params;
  return <CorporateDetail corporateId={corporateId} />;
}
