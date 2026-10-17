"use client";

import { Search } from "lucide-react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { ApiError } from "@/lib/api/client";
import { useMergePlace, usePlaceSearch } from "@/lib/api/hooks";
import type { PlaceHit, PlaceView } from "@/lib/api/types";
import { cn } from "@/lib/utils";
import { VerifiedMark } from "./place-list";

/** Merge this (duplicate) place into another one found by search; saved places and usage move with it. */
export function MergeDialog({ place, onClose }: { place: PlaceView; onClose: () => void }) {
  const t = useTranslations();
  const router = useRouter();
  const merge = useMergePlace(place.id);
  const [text, setText] = useState(place.nameEn || place.nameKm || "");
  const [q, setQ] = useState(text);
  const [target, setTarget] = useState<PlaceHit | null>(null);

  useEffect(() => {
    const id = setTimeout(() => setQ(text.trim()), 300);
    return () => clearTimeout(id);
  }, [text]);

  const results = usePlaceSearch(q);
  const hits = (results.data ?? []).filter((h) => h.id !== place.id);

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("places.mergeTitle")}</DialogTitle>
          <DialogDescription>{t("places.mergeHint", { name: place.nameKm || place.nameEn })}</DialogDescription>
        </DialogHeader>
        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input className="pl-9" autoFocus aria-label={t("common.search")} placeholder={t("places.searchPlaceholder")} value={text} onChange={(e) => setText(e.target.value)} />
        </div>
        <ul className="flex max-h-72 flex-col gap-1 overflow-y-auto" role="listbox" aria-label={t("places.mergeInto")}>
          {hits.length === 0 && q.length >= 2 && !results.isFetching && (
            <li className="p-3 text-sm text-muted-foreground">{t("places.empty")}</li>
          )}
          {hits.map((h) => (
            <li key={h.id}>
              <button
                type="button"
                role="option"
                aria-selected={target?.id === h.id}
                onClick={() => setTarget(h)}
                className={cn(
                  "flex w-full flex-col items-start gap-0.5 rounded-lg border px-3 py-2 text-left text-sm leading-normal hover:bg-muted",
                  target?.id === h.id && "border-primary bg-primary/5",
                )}
              >
                <span className="font-medium">{h.nameKm || h.nameEn}</span>
                <span className="flex flex-wrap gap-x-2 text-xs text-muted-foreground">
                  {h.nameKm && h.nameEn && <span>{h.nameEn}</span>}
                  <span>{t(`placeType.${h.type}`)}</span>
                  <VerifiedMark verified={h.verified} />
                </span>
              </button>
            </li>
          ))}
        </ul>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t("common.cancel")}</Button>
          <Button
            variant="destructive"
            disabled={!target || merge.isPending}
            onClick={() =>
              target &&
              merge.mutate(target.id, {
                onSuccess: () => {
                  toast.success(t("places.merged"));
                  router.push(`/places/${target.id}`);
                },
                onError: (e) => toast.error(e instanceof ApiError && e.detail ? e.detail : t("common.error")),
              })
            }
          >
            {t("places.mergeConfirm")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
