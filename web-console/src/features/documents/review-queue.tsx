"use client";

import { ExternalLink } from "lucide-react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { NativeSelect } from "@/components/native-select";
import { PageHeader } from "@/components/page/page-header";
import { Pagination } from "@/components/page/pagination";
import { ErrorState, LoadingRows, NotAllowed } from "@/components/page/state";
import { useSession } from "@/components/shell/session";
import { DriverStatusBadge } from "@/components/status-badge";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { useReviewQueue } from "@/lib/api/hooks";
import type { DocumentType, QueueItem } from "@/lib/api/types";
import { useDates } from "@/lib/format";
import { cn } from "@/lib/utils";
import { DocumentCard } from "./document-card";

const TYPES: DocumentType[] = ["NATIONAL_ID", "DRIVING_LICENSE", "PROFILE_PHOTO", "VEHICLE_REGISTRATION"];

/** Pending documents, oldest first; the selected one shows both sides side by side with approve/reject. */
export function ReviewQueue() {
  const t = useTranslations();
  const dates = useDates();
  const session = useSession();
  const [type, setType] = useState<DocumentType | "">("");
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const queue = useReviewQueue({ type: type || undefined, page, size: 20 });

  if (!session.can("reviewDocuments")) return <NotAllowed />;
  const items = queue.data?.content ?? [];
  const selected: QueueItem | undefined = items.find((i) => i.document.id === selectedId) ?? items[0];

  return (
    <div>
      <PageHeader
        title={t("review.title")}
        description={queue.data ? t("review.queue", { count: queue.data.totalElements }) : undefined}
        actions={
          <NativeSelect aria-label={t("vehicles.class")} value={type} onChange={(e) => { setType(e.target.value as DocumentType | ""); setPage(0); }} className="w-56">
            <option value="">{t("common.all")}</option>
            {TYPES.map((d) => (
              <option key={d} value={d}>{t(`docType.${d}`)}</option>
            ))}
          </NativeSelect>
        }
      />
      {queue.isPending ? (
        <LoadingRows />
      ) : queue.isError ? (
        <ErrorState error={queue.error} onRetry={() => queue.refetch()} />
      ) : items.length === 0 ? (
        <p className="rounded-lg border p-10 text-center text-muted-foreground">{t("review.empty")}</p>
      ) : (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-[320px_1fr]">
          <div>
            <ul className="flex flex-col divide-y rounded-lg border">
              {items.map((item) => (
                <li key={item.document.id}>
                  <button
                    type="button"
                    onClick={() => setSelectedId(item.document.id)}
                    className={cn(
                      "flex w-full flex-col gap-0.5 px-3 py-2.5 text-left hover:bg-muted",
                      selected?.document.id === item.document.id && "bg-muted",
                    )}
                  >
                    <span className="font-medium">{t(`docType.${item.document.type}`)}</span>
                    <span className="text-sm">{item.driver.fullName}</span>
                    <span className="text-xs text-muted-foreground">{dates.dateTime(item.document.uploadedAt)}</span>
                  </button>
                </li>
              ))}
            </ul>
            <Pagination page={queue.data.page} totalPages={queue.data.totalPages} totalElements={queue.data.totalElements} onPage={setPage} />
          </div>
          {selected && (
            <div className="flex flex-col gap-3">
              <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3">
                <div className="flex flex-col">
                  <span className="text-sm text-muted-foreground">{t("review.driver")}</span>
                  <span className="font-medium">{selected.driver.fullName} · {selected.driver.phone}</span>
                </div>
                <div className="flex items-center gap-2">
                  <DriverStatusBadge status={selected.driver.status} />
                  {(selected.document.files ?? []).length > 1 && <Badge variant="outline">{t("side.FRONT")} + {t("side.BACK")}</Badge>}
                  <Link href={`/drivers/${selected.driver.id}`} className={buttonVariants({ variant: "outline", size: "sm" })}>
                    <ExternalLink /> {t("review.openDriver")}
                  </Link>
                </div>
              </div>
              <DocumentCard
                key={selected.document.id}
                driverId={selected.driver.id}
                document={selected.document}
                onReviewed={() => {
                  // Continue with the next document in the list (the reviewed one leaves the queue).
                  const index = items.findIndex((i) => i.document.id === selected.document.id);
                  setSelectedId(items[index + 1]?.document.id ?? items[index - 1]?.document.id ?? null);
                }}
              />
            </div>
          )}
        </div>
      )}
    </div>
  );
}
