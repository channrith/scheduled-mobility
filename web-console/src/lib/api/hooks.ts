"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, query } from "./client";
import type {
  AuditView, DocumentType, DocumentStatus, DriverDetail, DriverStatus, DriverSummary, DocumentView, Me, Page,
  PreviewUrl, QueueItem, VehicleClass, VehicleListItem, VehicleStatus, VehicleView,
} from "./types";

const V1 = "/api/v1";

export const keys = {
  me: ["me"] as const,
  drivers: (filters: object) => ["drivers", filters] as const,
  driver: (id: string) => ["driver", id] as const,
  audit: (id: string) => ["driver", id, "audit"] as const,
  queue: (filters: object) => ["documents", filters] as const,
  vehicles: (filters: object) => ["vehicles", filters] as const,
};

export function useMe() {
  return useQuery({ queryKey: keys.me, queryFn: () => api.get<Me>(`${V1}/me`), staleTime: 60_000 });
}

export type DriverFilters = {
  status?: DriverStatus;
  vehicleClass?: VehicleClass;
  expiringSoon?: boolean;
  q?: string;
  page: number;
  size: number;
};

export function useDrivers(filters: DriverFilters) {
  return useQuery({
    queryKey: keys.drivers(filters),
    queryFn: () => api.get<Page<DriverSummary>>(`${V1}/admin/drivers${query(filters)}`),
    placeholderData: keepPreviousData,
  });
}

export function useDriver(id: string | undefined) {
  return useQuery({
    queryKey: keys.driver(id ?? ""),
    queryFn: () => api.get<DriverDetail>(`${V1}/admin/drivers/${id}`),
    enabled: !!id,
  });
}

export function useDriverAudit(id: string, enabled: boolean) {
  return useQuery({
    queryKey: keys.audit(id),
    queryFn: () => api.get<AuditView[]>(`${V1}/admin/drivers/${id}/audit`),
    enabled,
  });
}

export function useReviewQueue(filters: { status?: DocumentStatus; type?: DocumentType; page: number; size: number }) {
  return useQuery({
    queryKey: keys.queue(filters),
    queryFn: () => api.get<Page<QueueItem>>(`${V1}/admin/documents${query(filters)}`),
    placeholderData: keepPreviousData,
  });
}

export type VehicleFilters = { q?: string; vehicleClass?: VehicleClass; status?: VehicleStatus; page: number; size: number };

export function useVehicles(filters: VehicleFilters, enabled = true) {
  return useQuery({
    queryKey: keys.vehicles(filters),
    queryFn: () => api.get<Page<VehicleListItem>>(`${V1}/admin/vehicles${query(filters)}`),
    placeholderData: keepPreviousData,
    enabled,
  });
}

/** Mutations on a driver refresh that driver, the lists and the audit trail. */
function useDriverMutation<TVars, TResult>(driverId: string | undefined, fn: (vars: TVars) => Promise<TResult>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: async (result) => {
      if (driverId) {
        if (result && typeof result === "object" && "status" in result && "documents" in result) {
          client.setQueryData(keys.driver(driverId), result);
        }
        await client.invalidateQueries({ queryKey: ["driver", driverId] });
      }
      await client.invalidateQueries({ queryKey: ["drivers"] });
      await client.invalidateQueries({ queryKey: ["documents"] });
      await client.invalidateQueries({ queryKey: ["vehicles"] });
    },
  });
}

export type RegisterDriverInput = {
  phone: string;
  fullName: string;
  nationalId?: string;
  licenseNumber?: string;
  homeZone?: string;
  preferredLanguage?: "KM" | "EN";
  bankName?: string;
  bankAccountName?: string;
  bankAccountNumber?: string;
};

export function useRegisterDriver() {
  return useDriverMutation(undefined, (input: RegisterDriverInput) => api.post<DriverDetail>(`${V1}/admin/drivers`, input));
}

export function useUpdateDriver(id: string) {
  return useDriverMutation(id, (input: Partial<RegisterDriverInput> & { version: number }) =>
    api.patch<DriverDetail>(`${V1}/admin/drivers/${id}`, input),
  );
}

export type DriverAction = "submit-documents" | "training" | "approve" | "reject" | "suspend" | "reinstate";

export function useDriverAction(id: string) {
  return useDriverMutation(id, ({ action, reason, noticeAt }: { action: DriverAction; reason?: string; noticeAt?: string }) =>
    api.post<DriverDetail>(`${V1}/admin/drivers/${id}/${action}`, { reason, noticeAt }),
  );
}

export function useUploadDocument(driverId: string) {
  return useDriverMutation(driverId, (form: FormData) =>
    api.upload<DocumentView>(`${V1}/admin/drivers/${driverId}/documents`, form),
  );
}

export function useReviewDocument(driverId: string) {
  return useDriverMutation(driverId, ({ documentId, approve, reason }: { documentId: string; approve: boolean; reason?: string }) =>
    api.post<DocumentView>(
      `${V1}/admin/drivers/${driverId}/documents/${documentId}/${approve ? "approve" : "reject"}`,
      approve ? {} : { reason },
    ),
  );
}

export function useAssignVehicle(driverId: string) {
  return useDriverMutation(driverId, (input: { vehicleId: string; validFrom?: string; validTo?: string }) =>
    api.post<DriverDetail>(`${V1}/admin/drivers/${driverId}/vehicle-assignments`, input),
  );
}

export function useEndAssignment(driverId: string) {
  return useDriverMutation(driverId, (assignmentId: string) =>
    api.del<DriverDetail>(`${V1}/admin/drivers/${driverId}/vehicle-assignments/${assignmentId}`),
  );
}

export type VehicleInput = {
  plateNumber: string;
  vehicleClass: VehicleClass;
  make?: string;
  model?: string;
  color?: string;
  modelYear?: number;
  seats: number;
  ownership: string;
  ownerName?: string;
  ownerPhone?: string;
};

export function useSaveVehicle() {
  return useDriverMutation(undefined, ({ id, ...input }: VehicleInput & { id?: string; version?: number; status?: VehicleStatus }) =>
    id ? api.put<VehicleView>(`${V1}/admin/vehicles/${id}`, input) : api.post<VehicleView>(`${V1}/admin/vehicles`, input),
  );
}

export function fetchPreviewUrl(driverId: string, documentId: string, side: "front" | "back") {
  return api.post<PreviewUrl>(`${V1}/admin/drivers/${driverId}/documents/${documentId}/files/${side}/preview-url`);
}
