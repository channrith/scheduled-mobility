"use client";

import { createColumnHelper, tableFeatures, useTable } from "@tanstack/react-table";
import { Pencil, Plus, Search } from "lucide-react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { useEffect, useMemo, useState } from "react";
import { NativeSelect } from "@/components/native-select";
import { PageHeader } from "@/components/page/page-header";
import { Pagination } from "@/components/page/pagination";
import { ErrorState, LoadingRows, NotAllowed } from "@/components/page/state";
import { RequirePermission, useSession } from "@/components/shell/session";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useVehicles } from "@/lib/api/hooks";
import { VEHICLE_CLASSES, type VehicleClass, type VehicleListItem, type VehicleStatus, type VehicleView } from "@/lib/api/types";
import { useUrlState } from "@/lib/use-url-state";
import { VehicleForm } from "./vehicle-form";

const features = tableFeatures({});
const helper = createColumnHelper<typeof features, VehicleListItem>();
const emptyRows: VehicleListItem[] = [];

export function VehicleList() {
  const t = useTranslations();
  const session = useSession();
  const url = useUrlState();
  const [search, setSearch] = useState(url.get("q") ?? "");
  const [editing, setEditing] = useState<VehicleView | "new" | null>(null);

  useEffect(() => {
    const id = setTimeout(() => {
      if ((url.get("q") ?? "") !== search) url.set({ q: search, page: undefined });
    }, 300);
    return () => clearTimeout(id);
  }, [search, url]);

  const filters = {
    q: url.get("q"),
    vehicleClass: url.get("vehicleClass") as VehicleClass | undefined,
    status: url.get("status") as VehicleStatus | undefined,
    page: Number(url.get("page") ?? 0),
    size: 25,
  };
  const result = useVehicles(filters, session.can("viewVehicles"));
  const canEdit = session.can("manageVehicles");

  const columns = useMemo(
    () =>
      helper.columns([
        helper.accessor((row) => row.vehicle?.plateNumber, {
          id: "plate",
          header: () => t("vehicles.plate"),
          cell: (info) => <span className="font-mono">{info.getValue()}</span>,
        }),
        helper.accessor((row) => row.vehicle?.vehicleClass, {
          id: "class",
          header: () => t("vehicles.class"),
          cell: (info) => (info.getValue() ? t(`vehicleClass.${info.getValue()}`) : "—"),
        }),
        helper.accessor((row) => row.vehicle, {
          id: "owner",
          header: () => t("vehicles.owner"),
          cell: (info) => {
            const v = info.getValue();
            return (
              <div className="flex flex-col">
                <span>{v?.ownership ? t(`ownership.${v.ownership}`) : "—"}</span>
                {v?.ownerName && <span className="text-xs text-muted-foreground">{v.ownerName} · {v.ownerPhone}</span>}
              </div>
            );
          },
        }),
        helper.accessor("assignedDriverName", {
          header: () => t("vehicles.assignedDriver"),
          cell: (info) =>
            info.row.original.assignedDriverId ? (
              <Link className="hover:underline" href={`/drivers/${info.row.original.assignedDriverId}`}>{info.getValue()}</Link>
            ) : (
              "—"
            ),
        }),
        helper.accessor((row) => row.vehicle?.status, {
          id: "status",
          header: () => t("vehicles.status"),
          cell: (info) => (
            <Badge variant={info.getValue() === "ACTIVE" ? "secondary" : "outline"}>{t(`vehicleStatus.${info.getValue()}`)}</Badge>
          ),
        }),
        helper.display({
          id: "actions",
          header: () => <span className="sr-only">{t("common.actions")}</span>,
          cell: (info) =>
            canEdit ? (
              <Button variant="ghost" size="icon-sm" aria-label={t("common.edit")} onClick={() => setEditing(info.row.original.vehicle as VehicleView)}>
                <Pencil />
              </Button>
            ) : null,
        }),
      ]),
    [t, canEdit],
  );
  const table = useTable({ features, columns, data: result.data?.content ?? emptyRows });

  if (!session.can("viewVehicles")) return <NotAllowed />;

  return (
    <div>
      <PageHeader
        title={t("vehicles.title")}
        actions={
          <RequirePermission permission="manageVehicles">
            <Button onClick={() => setEditing("new")}>
              <Plus /> {t("vehicles.new")}
            </Button>
          </RequirePermission>
        }
      />
      <div className="mb-3 grid grid-cols-1 gap-2 sm:grid-cols-[1fr_200px_160px]">
        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input className="pl-9" aria-label={t("common.search")} placeholder={t("vehicles.searchPlaceholder")} value={search} onChange={(e) => setSearch(e.target.value)} />
        </div>
        <NativeSelect aria-label={t("vehicles.class")} value={filters.vehicleClass ?? ""} onChange={(e) => url.set({ vehicleClass: e.target.value, page: undefined })}>
          <option value="">{t("vehicles.class")}: {t("common.all")}</option>
          {VEHICLE_CLASSES.map((c) => (
            <option key={c} value={c}>{t(`vehicleClass.${c}`)}</option>
          ))}
        </NativeSelect>
        <NativeSelect aria-label={t("vehicles.status")} value={filters.status ?? ""} onChange={(e) => url.set({ status: e.target.value, page: undefined })}>
          <option value="">{t("vehicles.status")}: {t("common.all")}</option>
          <option value="ACTIVE">{t("vehicleStatus.ACTIVE")}</option>
          <option value="INACTIVE">{t("vehicleStatus.INACTIVE")}</option>
        </NativeSelect>
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
                    <TableCell colSpan={columns.length} className="py-10 text-center text-muted-foreground">{t("vehicles.empty")}</TableCell>
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
      <Dialog open={editing !== null} onOpenChange={(open) => !open && setEditing(null)}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{editing === "new" ? t("vehicles.new") : t("vehicles.editTitle")}</DialogTitle>
          </DialogHeader>
          {editing !== null && (
            <VehicleForm vehicle={editing === "new" ? undefined : editing} onSaved={() => setEditing(null)} onCancel={() => setEditing(null)} />
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
