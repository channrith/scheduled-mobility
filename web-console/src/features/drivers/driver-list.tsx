"use client";

import { createColumnHelper, tableFeatures, useTable } from "@tanstack/react-table";
import { AlertTriangle, Search } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { useEffect, useMemo, useState } from "react";
import { NativeSelect } from "@/components/native-select";
import { PageHeader } from "@/components/page/page-header";
import { Pagination } from "@/components/page/pagination";
import { ErrorState, LoadingRows } from "@/components/page/state";
import { RequirePermission } from "@/components/shell/session";
import { DriverStatusBadge } from "@/components/status-badge";
import { buttonVariants } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { useDrivers } from "@/lib/api/hooks";
import { DRIVER_STATUSES, VEHICLE_CLASSES, type DriverStatus, type DriverSummary, type VehicleClass } from "@/lib/api/types";
import { useDates } from "@/lib/format";
import { useUrlState } from "@/lib/use-url-state";

const features = tableFeatures({});
const helper = createColumnHelper<typeof features, DriverSummary>();
const emptyRows: DriverSummary[] = [];

export function DriverList() {
  const t = useTranslations();
  const router = useRouter();
  const dates = useDates();
  const url = useUrlState();
  const [search, setSearch] = useState(url.get("q") ?? "");

  // Debounce typing into the URL (and so into the query).
  useEffect(() => {
    const id = setTimeout(() => {
      if ((url.get("q") ?? "") !== search) url.set({ q: search, page: undefined });
    }, 300);
    return () => clearTimeout(id);
  }, [search, url]);

  const filters = {
    q: url.get("q"),
    status: url.get("status") as DriverStatus | undefined,
    vehicleClass: url.get("vehicleClass") as VehicleClass | undefined,
    expiringSoon: url.get("expiringSoon") === "true",
    page: Number(url.get("page") ?? 0),
    size: 25,
  };
  const result = useDrivers(filters);

  const columns = useMemo(
    () =>
      helper.columns([
        helper.accessor("fullName", {
          header: () => t("drivers.name"),
          cell: (info) => (
            <div className="flex flex-col">
              <Link href={`/drivers/${info.row.original.id}`} className="font-medium hover:underline">
                {info.getValue()}
              </Link>
              <span className="text-xs text-muted-foreground">{info.row.original.phone}</span>
            </div>
          ),
        }),
        helper.accessor("status", {
          header: () => t("drivers.status"),
          cell: (info) => <DriverStatusBadge status={info.getValue() as DriverStatus} />,
        }),
        helper.accessor("homeZone", {
          header: () => t("drivers.zone"),
          cell: (info) => (info.getValue() ? t(`zone.${info.getValue()}`) : "—"),
        }),
        helper.accessor("vehicle", {
          header: () => t("drivers.vehicle"),
          cell: (info) => {
            const v = info.getValue();
            return v ? (
              <div className="flex flex-col">
                <span className="font-mono text-sm">{v.plateNumber}</span>
                <span className="text-xs text-muted-foreground">{t(`vehicleClass.${v.vehicleClass}`)}</span>
              </div>
            ) : (
              "—"
            );
          },
        }),
        helper.accessor("documentsExpiringSoon", {
          header: () => <span className="sr-only">{t("drivers.expiringSoon")}</span>,
          cell: (info) =>
            info.getValue() ? (
              <Tooltip>
                <TooltipTrigger render={<span className="inline-flex text-amber-600" aria-label={t("drivers.expiringBadge")} />}>
                  <AlertTriangle className="size-4" />
                </TooltipTrigger>
                <TooltipContent>{t("drivers.expiringBadge")}</TooltipContent>
              </Tooltip>
            ) : null,
        }),
        helper.accessor("createdAt", {
          header: () => t("drivers.registered"),
          cell: (info) => <span className="whitespace-nowrap">{dates.date(info.getValue())}</span>,
        }),
      ]),
    [t, dates],
  );
  const table = useTable({ features, columns, data: result.data?.content ?? emptyRows });

  return (
    <div>
      <PageHeader
        title={t("drivers.title")}
        actions={
          <RequirePermission permission="registerDrivers">
            <Link href="/drivers/new" className={buttonVariants()}>
              {t("nav.registerDriver")}
            </Link>
          </RequirePermission>
        }
      />
      <div className="mb-3 grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-[1fr_200px_200px_auto]">
        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            aria-label={t("common.search")}
            placeholder={t("drivers.searchPlaceholder")}
            className="pl-9"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        <NativeSelect
          aria-label={t("drivers.status")}
          value={filters.status ?? ""}
          onChange={(e) => url.set({ status: e.target.value, page: undefined })}
        >
          <option value="">{t("drivers.status")}: {t("common.all")}</option>
          {DRIVER_STATUSES.map((s) => (
            <option key={s} value={s}>{t(`status.${s}`)}</option>
          ))}
        </NativeSelect>
        <NativeSelect
          aria-label={t("drivers.vehicleClass")}
          value={filters.vehicleClass ?? ""}
          onChange={(e) => url.set({ vehicleClass: e.target.value, page: undefined })}
        >
          <option value="">{t("drivers.vehicleClass")}: {t("common.all")}</option>
          {VEHICLE_CLASSES.map((c) => (
            <option key={c} value={c}>{t(`vehicleClass.${c}`)}</option>
          ))}
        </NativeSelect>
        <Label className="flex h-10 items-center gap-2 rounded-lg border px-3 font-normal">
          <Checkbox
            checked={filters.expiringSoon}
            onCheckedChange={(checked) => url.set({ expiringSoon: checked === true, page: undefined })}
          />
          {t("drivers.expiringSoon")}
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
                      <TableHead key={header.id} className="leading-normal">
                        {header.isPlaceholder ? null : <table.FlexRender header={header} />}
                      </TableHead>
                    ))}
                  </TableRow>
                ))}
              </TableHeader>
              <TableBody>
                {table.getRowModel().rows.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={columns.length} className="py-10 text-center text-muted-foreground">
                      {t("drivers.empty")}
                    </TableCell>
                  </TableRow>
                ) : (
                  table.getRowModel().rows.map((row) => (
                    <TableRow
                      key={row.id}
                      className="cursor-pointer"
                      onClick={(e) => {
                        if (!(e.target as HTMLElement).closest("a")) router.push(`/drivers/${row.original.id}`);
                      }}
                    >
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
          <Pagination
            page={result.data.page}
            totalPages={result.data.totalPages}
            totalElements={result.data.totalElements}
            onPage={(page) => url.set({ page })}
          />
        </>
      )}
    </div>
  );
}
