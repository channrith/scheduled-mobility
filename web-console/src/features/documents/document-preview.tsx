"use client";

import { useQuery } from "@tanstack/react-query";
import { ExternalLink } from "lucide-react";
import { useTranslations } from "next-intl";
import { Skeleton } from "@/components/ui/skeleton";
import { fetchPreviewUrl } from "@/lib/api/hooks";
import { previewSrc } from "@/lib/api/client";
import type { DocumentView } from "@/lib/api/types";

/**
 * Shows each side through a short-lived signed link (no storage path, audited when issued). Links are
 * reused for a few minutes and re-issued after that.
 */
export function DocumentPreview({ driverId, document, className }: { driverId: string; document: DocumentView; className?: string }) {
  const t = useTranslations();
  return (
    <div className={className ?? "grid grid-cols-1 gap-3 md:grid-cols-2"}>
      {(document.files ?? []).map((file) => (
        <SidePreview
          key={file.side}
          driverId={driverId}
          documentId={document.id}
          side={file.side === "BACK" ? "back" : "front"}
          isPdf={file.contentType === "application/pdf"}
          label={t(`side.${file.side}`)}
        />
      ))}
    </div>
  );
}

function SidePreview({ driverId, documentId, side, isPdf, label }: {
  driverId: string;
  documentId: string;
  side: "front" | "back";
  isPdf: boolean;
  label: string;
}) {
  const link = useQuery({
    queryKey: ["preview", documentId, side],
    queryFn: () => fetchPreviewUrl(driverId, documentId, side),
    staleTime: 4 * 60_000,
    gcTime: 4 * 60_000,
  });
  return (
    <figure className="flex flex-col gap-1">
      <figcaption className="flex items-center justify-between text-sm text-muted-foreground">
        {label}
        {link.data && (
          <a href={previewSrc(link.data.url)} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 hover:underline">
            <ExternalLink className="size-3.5" />
          </a>
        )}
      </figcaption>
      <div className="flex aspect-[3/2] items-center justify-center overflow-hidden rounded-md border bg-muted">
        {!link.data ? (
          <Skeleton className="h-full w-full" />
        ) : isPdf ? (
          <iframe src={previewSrc(link.data.url)} title={label} className="h-full w-full" />
        ) : (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={previewSrc(link.data.url)} alt={label} referrerPolicy="no-referrer" className="h-full w-full object-contain" />
        )}
      </div>
    </figure>
  );
}
