"use client";

import { ChevronLeft, ChevronRight } from "lucide-react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";

export function Pagination({
  page,
  totalPages,
  totalElements,
  onPage,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  onPage: (page: number) => void;
}) {
  const t = useTranslations("common");
  return (
    <div className="flex items-center justify-between gap-2 py-3 text-sm text-muted-foreground">
      <span>{t("results", { count: totalElements })}</span>
      <div className="flex items-center gap-2">
        <span>{t("page", { page: totalPages === 0 ? 0 : page + 1, total: totalPages })}</span>
        <Button variant="outline" size="icon" aria-label={t("previous")} disabled={page <= 0} onClick={() => onPage(page - 1)}>
          <ChevronLeft />
        </Button>
        <Button variant="outline" size="icon" aria-label={t("next")} disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>
          <ChevronRight />
        </Button>
      </div>
    </div>
  );
}
