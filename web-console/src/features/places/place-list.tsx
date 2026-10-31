"use client";

import { createColumnHelper, tableFeatures, useTable } from "@tanstack/react-table";
import { BadgeCheck, CircleDashed, Plus, Search } from "lucide-react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { useEffect, useMemo, useState } from "react";
import { NativeSelect } from "@/components/native-select";
import { PageHeader } from "@/components/page/page-header";
import { Pagination } from "@/components/page/pagination";
import { ErrorState, LoadingRows, NotAllowed } from "@/components/page/state";
import { useSession } from "@/components/shell/session";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { usePlaces } from "@/lib/api/hooks";
import { PLACE_TYPES, type PlaceType, type PlaceView } from "@/lib/api/types";
import { useDates } from "@/lib/format";
import { useUrlState } from "@/lib/use-url-state";

const features = tableFeatures({});
const helper = createColumnHelper<typeof features, PlaceView>();
const emptyRows: PlaceView[] = [];

export function VerifiedMark({ verified }: { verified: boolean }) {
  const t = useTranslations("places");
  return verified ? (
    <span className="inline-flex items-center gap-1 text-emerald-700 dark:text-emerald-400">
      <BadgeCheck className="size-4" /> {t("verified")}
    </span>
  ) : (
    <span className="inline-flex items-center gap-1 text-amber-700 dark:text-amber-400">
      <CircleDashed className="size-4" /> {t("unverified")}
    </span>
  );
}

export function PlaceList() {
  const t = useTranslations();
  const dates = useDates();
  const session = useSession();
  const url = useUrlState();
  const [search, setSearch] = useState(url.get("q") ?? "");

  useEffect(() => {
    const id = setTimeout(() => {
      if ((url.get("q") ?? "") !== search) url.set({ q: search, page: undefined });
    }, 300);
    return () => clearTimeout(id);
  }, [search, url]);

  const unverified = url.get("unverified") === "true";
  const filters = {
    q: url.get("q"),
    type: url.get("type") as PlaceType | undefined,
    verified: unverified ? false : undefined,
    page: Number(url.get("page") ?? 0),
    size: 25,
  };
  const result = usePlaces(filters, session.can("managePlaces"));

  const columns = useMemo(
    () =>
      helper.columns([
        helper.accessor("nameKm", {
          header: () => t("places.name"),
          cell: (info) => (
            <Link className="flex flex-col hover:underline" href={`/places/${info.row.original.id}`}>
              <span className="font-medium">{info.getValue() || info.row.original.nameEn}</span>
              {info.getValue() && info.row.original.nameEn && <span className="text-xs text-muted-foreground">{info.row.original.nameEn}</span>}
            </Link>
          ),
        }),
        helper.accessor("type", {
          header: () => t("places.type"),
          cell: (info) => t(`placeType.${info.getValue()}`),
        }),
        helper.accessor("verified", {
          header: () => t("places.verification"),
          cell: (info) => <VerifiedMark verified={info.getValue()} />,
        }),
        helper.accessor("usageCount", {
          header: () => t("places.usage"),
          cell: (info) => <span className="tabular-nums">{info.getValue()}</span>,
        }),
        helper.accessor("updatedAt", {
          header: () => t("places.updatedAt"),
          cell: (info) => <span className="text-muted-foreground">{dates.date(info.getValue())}</span>,
        }),
      ]),
    [t, dates],
  );
  const table = useTable({ features, columns, data: result.data?.content ?? emptyRows });

  if (!session.can("managePlaces")) return <NotAllowed />;

  return (
    <div>
      <PageHeader
        title={t("places.title")}
        description={t("places.subtitle")}
        actions={
          <Button nativeButton={false} render={<Link href="/places/new" />}>
            <Plus /> {t("places.new")}
          </Button>
        }
      />
      <div className="mb-3 grid grid-cols-1 gap-2 sm:grid-cols-[1fr_200px_auto]">
        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input className="pl-9" aria-label={t("common.search")} placeholder={t("places.searchPlaceholder")} value={search} onChange={(e) => setSearch(e.target.value)} />
        </div>
        <NativeSelect aria-label={t("places.type")} value={filters.type ?? ""} onChange={(e) => url.set({ type: e.target.value, page: undefined })}>
          <option value="">{t("places.type")}: {t("common.all")}</option>
          {PLACE_TYPES.map((p) => (
            <option key={p} value={p}>{t(`placeType.${p}`)}</option>
          ))}
        </NativeSelect>
        <Label className="flex h-10 items-center gap-2 rounded-lg border px-3 font-normal">
          <Checkbox checked={unverified} onCheckedChange={(checked) => url.set({ unverified: checked === true, page: undefined })} />
          {t("places.onlyUnverified")}
        </Label>
      </div>
      {result.isPending ? (
        <LoadingRows />
      ) : result.isError ? (
        <ErrorState error={result.error} onRetry={() => result.refetch()} />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                {table.getHeaderGroups().map((group) => (
                  <TableRow key={group.id}>
                    {group.headers.map((header) => (
                      <TableHead key={header.id}>{header.isPlaceholder ? null : <table.FlexRender header={header} />}</TableHead>
                    ))}
                  </TableRow>
                ))}
              </TableHeader>
              <TableBody>
                {table.getRowModel().rows.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={columns.length} className="py-10 text-center text-muted-foreground">{t("places.empty")}</TableCell>
                  </TableRow>
                ) : (
                  table.getRowModel().rows.map((row) => (
                    <TableRow key={row.id}>
                      {row.getAllCells().map((cell) => (
                        <TableCell key={cell.id} className="py-2.5 leading-normal">
                          <table.FlexRender cell={cell} />
                        </TableCell>
                      ))}
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </div>
          <Pagination page={result.data.page} totalPages={result.data.totalPages} totalElements={result.data.totalElements} onPage={(page) => url.set({ page })} />
        </>
      )}
    </div>
  );
}
