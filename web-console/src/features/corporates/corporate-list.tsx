"use client";

import { createColumnHelper, tableFeatures, useTable } from "@tanstack/react-table";
import { Plus, Search } from "lucide-react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { useEffect, useMemo, useState } from "react";
import { NativeSelect } from "@/components/native-select";
import { PageHeader } from "@/components/page/page-header";
import { Pagination } from "@/components/page/pagination";
import { ErrorState, LoadingRows, NotAllowed } from "@/components/page/state";
import { RequirePermission, useSession } from "@/components/shell/session";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useCorporates } from "@/lib/api/hooks";
import type { AccountStatus, AccountView } from "@/lib/api/types";
import { formatMoney } from "@/lib/money";
import { useUrlState } from "@/lib/use-url-state";
import { AccountStatusBadge } from "./account-status-badge";

const features = tableFeatures({});
const helper = createColumnHelper<typeof features, AccountView>();
const emptyRows: AccountView[] = [];

export function CorporateList() {
  const t = useTranslations();
  const session = useSession();
  const url = useUrlState();
  const [search, setSearch] = useState(url.get("q") ?? "");

  useEffect(() => {
    const id = setTimeout(() => {
      if ((url.get("q") ?? "") !== search) url.set({ q: search, page: undefined });
    }, 300);
    return () => clearTimeout(id);
  }, [search, url]);

  const filters = {
    q: url.get("q"),
    status: url.get("status") as AccountStatus | undefined,
    page: Number(url.get("page") ?? 0),
    size: 25,
  };
  const result = useCorporates(filters, session.can("viewCorporates"));

  const columns = useMemo(
    () =>
      helper.columns([
        helper.accessor("legalName", {
          header: () => t("corporates.name"),
          cell: (info) => (
            <Link className="flex flex-col hover:underline" href={`/corporates/${info.row.original.id}`}>
              <span className="font-medium">{info.row.original.displayName || info.getValue()}</span>
              {info.row.original.displayName && <span className="text-xs text-muted-foreground">{info.getValue()}</span>}
            </Link>
          ),
        }),
        helper.accessor("taxId", {
          header: () => t("corporates.taxId"),
          cell: (info) => <span className="font-mono">{info.getValue() || "—"}</span>,
        }),
        helper.accessor("paymentTermsDays", {
          header: () => t("corporates.paymentTerms"),
          cell: (info) => t("corporates.days", { count: info.getValue() }),
        }),
        helper.accessor("creditLimitMinor", {
          header: () => t("corporates.creditLimitShort"),
          cell: (info) => formatMoney(info.getValue(), info.row.original.currency),
        }),
        helper.accessor("status", {
          header: () => t("corporates.status"),
          cell: (info) => <AccountStatusBadge status={info.getValue()} />,
        }),
      ]),
    [t],
  );
  const table = useTable({ features, columns, data: result.data?.content ?? emptyRows });

  if (!session.can("viewCorporates")) return <NotAllowed />;

  return (
    <div>
      <PageHeader
        title={t("corporates.title")}
        actions={
          <RequirePermission permission="manageCorporates">
            <Button nativeButton={false} render={<Link href="/corporates/new" />}>
              <Plus /> {t("corporates.new")}
            </Button>
          </RequirePermission>
        }
      />
      <div className="mb-3 grid grid-cols-1 gap-2 sm:grid-cols-[1fr_200px]">
        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input className="pl-9" aria-label={t("common.search")} placeholder={t("corporates.searchPlaceholder")} value={search} onChange={(e) => setSearch(e.target.value)} />
        </div>
        <NativeSelect aria-label={t("corporates.status")} value={filters.status ?? ""} onChange={(e) => url.set({ status: e.target.value, page: undefined })}>
          <option value="">{t("corporates.status")}: {t("common.all")}</option>
          <option value="ACTIVE">{t("accountStatus.ACTIVE")}</option>
          <option value="SUSPENDED">{t("accountStatus.SUSPENDED")}</option>
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
                    <TableCell colSpan={columns.length} className="py-10 text-center text-muted-foreground">{t("corporates.empty")}</TableCell>
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
