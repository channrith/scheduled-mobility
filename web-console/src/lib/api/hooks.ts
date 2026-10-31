"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, query } from "./client";
import type {
  AccountDetail, AccountStatus, AccountView, AuditView, CostCenterView, DocumentType, DocumentStatus, DriverDetail,
  DriverStatus, DriverSummary, DocumentView, HistoryEntryView, LatLng, Me, MemberPolicy, MemberRole, MemberView, Page,
  PlaceHit, PlaceType, PlaceView, PreviewUrl, PriceLineView, QueueItem, QuoteView, RuleView, SettingView, SettingsView,
  VehicleClass, VehicleListItem, VehicleStatus, VehicleView, ZoneType, ZoneView,
} from "./types";

const V1 = "/api/v1";

export const keys = {
  me: ["me"] as const,
  drivers: (filters: object) => ["drivers", filters] as const,
  driver: (id: string) => ["driver", id] as const,
  audit: (id: string) => ["driver", id, "audit"] as const,
  queue: (filters: object) => ["documents", filters] as const,
  vehicles: (filters: object) => ["vehicles", filters] as const,
  corporates: (filters: object) => ["corporates", filters] as const,
  corporate: (id: string) => ["corporate", id] as const,
  members: (id: string) => ["corporate", id, "members"] as const,
  costCenters: (id: string) => ["corporate", id, "cost-centers"] as const,
  places: (filters: object) => ["places", filters] as const,
  place: (id: string) => ["place", id] as const,
  placeSearch: (q: string) => ["places", "search", q] as const,
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

// --- Corporate accounts -------------------------------------------------------------------------

export type CorporateFilters = { q?: string; status?: AccountStatus; page: number; size: number };

export function useCorporates(filters: CorporateFilters, enabled = true) {
  return useQuery({
    queryKey: keys.corporates(filters),
    queryFn: () => api.get<Page<AccountView>>(`${V1}/admin/corporates${query(filters)}`),
    placeholderData: keepPreviousData,
    enabled,
  });
}

export function useCorporate(id: string) {
  return useQuery({ queryKey: keys.corporate(id), queryFn: () => api.get<AccountDetail>(`${V1}/admin/corporates/${id}`) });
}

export function useMembers(corporateId: string) {
  return useQuery({
    queryKey: keys.members(corporateId),
    queryFn: () => api.get<MemberView[]>(`${V1}/corporates/${corporateId}/members`),
  });
}

export function useCostCenters(corporateId: string) {
  return useQuery({
    queryKey: keys.costCenters(corporateId),
    queryFn: () => api.get<CostCenterView[]>(`${V1}/corporates/${corporateId}/cost-centers`),
  });
}

/** Changes to a company refresh everything about it and the account list. */
function useCorporateMutation<TVars, TResult>(corporateId: string | undefined, fn: (vars: TVars) => Promise<TResult>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: corporateId ? ["corporate", corporateId] : ["corporate"] });
      await client.invalidateQueries({ queryKey: ["corporates"] });
    },
  });
}

export type AccountInput = {
  legalName: string;
  displayName?: string;
  taxId?: string;
  paymentTermsDays: number;
  creditLimitMinor: number;
  currency: string;
  serviceFeePct: number;
};

export function useSaveCorporate() {
  return useCorporateMutation(undefined, ({ id, ...input }: AccountInput & { id?: string; version?: number }) =>
    id ? api.put<AccountView>(`${V1}/admin/corporates/${id}`, input) : api.post<AccountView>(`${V1}/admin/corporates`, input),
  );
}

export function useCorporateStatus(id: string) {
  return useCorporateMutation(id, ({ action, reason }: { action: "suspend" | "reactivate"; reason: string }) =>
    api.post<AccountView>(`${V1}/admin/corporates/${id}/${action}`, { reason }),
  );
}

export function useAddCorporateAdmin() {
  return useCorporateMutation(undefined, ({ corporateId, ...input }: { corporateId: string; phone: string; fullName: string }) =>
    api.post<MemberView>(`${V1}/admin/corporates/${corporateId}/admins`, input),
  );
}

export type MemberInput = {
  role: MemberRole;
  costCenterId?: string;
  employeeRef?: string;
  policy?: MemberPolicy;
};

export function useSaveMember(corporateId: string) {
  return useCorporateMutation(
    corporateId,
    ({ id, ...input }: MemberInput & { id?: string; version?: number; phone?: string; fullName?: string }) =>
      id
        ? api.put<MemberView>(`${V1}/corporates/${corporateId}/members/${id}`, input)
        : api.post<MemberView>(`${V1}/corporates/${corporateId}/members`, input),
  );
}

export function useRemoveMember(corporateId: string) {
  return useCorporateMutation(corporateId, (memberId: string) =>
    api.del<void>(`${V1}/corporates/${corporateId}/members/${memberId}`),
  );
}

export function useSaveCostCenter(corporateId: string) {
  return useCorporateMutation(
    corporateId,
    ({ id, ...input }: { id?: string; version?: number; code?: string; name: string; active?: boolean }) =>
      id
        ? api.put<CostCenterView>(`${V1}/corporates/${corporateId}/cost-centers/${id}`, input)
        : api.post<CostCenterView>(`${V1}/corporates/${corporateId}/cost-centers`, input),
  );
}

// --- Places -------------------------------------------------------------------------------------

export type PlaceFilters = { q?: string; verified?: boolean; type?: PlaceType; page: number; size: number };

export function usePlaces(filters: PlaceFilters, enabled = true) {
  return useQuery({
    queryKey: keys.places(filters),
    // `verified=false` must be sent, so it is passed as a string (query() drops `false`).
    queryFn: () =>
      api.get<Page<PlaceView>>(
        `${V1}/admin/places${query({ ...filters, verified: filters.verified === undefined ? undefined : String(filters.verified) })}`,
      ),
    placeholderData: keepPreviousData,
    enabled,
  });
}

export function usePlace(id: string | undefined) {
  return useQuery({
    queryKey: keys.place(id ?? ""),
    queryFn: () => api.get<PlaceView>(`${V1}/admin/places/${id}`),
    enabled: !!id,
  });
}

export function usePlaceSearch(q: string) {
  return useQuery({
    queryKey: keys.placeSearch(q),
    queryFn: () => api.get<PlaceHit[]>(`${V1}/places/search${query({ q, limit: 8 })}`),
    enabled: q.trim().length >= 2,
    placeholderData: keepPreviousData,
  });
}

export type PlaceInput = {
  nameKm?: string;
  nameEn?: string;
  latitude: number;
  longitude: number;
  type: PlaceType;
  pickupNotesKm?: string;
  pickupNotesEn?: string;
  verified: boolean;
};

function usePlaceMutation<TVars>(fn: (vars: TVars) => Promise<PlaceView>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: async (saved) => {
      client.setQueryData(keys.place(saved.id), saved);
      await client.invalidateQueries({ queryKey: ["places"] });
    },
  });
}

export function useSavePlace() {
  return usePlaceMutation(({ id, ...input }: PlaceInput & { id?: string; version?: number }) =>
    id ? api.put<PlaceView>(`${V1}/admin/places/${id}`, input) : api.post<PlaceView>(`${V1}/admin/places`, input),
  );
}

export function useMergePlace(id: string) {
  return usePlaceMutation((intoPlaceId: string) => api.post<PlaceView>(`${V1}/admin/places/${id}/merge`, { intoPlaceId }));
}

// --- Pricing ------------------------------------------------------------------------------------

export function usePricingCities() {
  return useQuery({ queryKey: ["pricing", "cities"], queryFn: () => api.get<string[]>(`${V1}/admin/pricing/cities`), staleTime: 5 * 60_000 });
}

export type LineFilters = { city?: string; vehicleClass?: VehicleClass; corporateId?: string };

export function usePriceLines(filters: LineFilters, enabled = true) {
  return useQuery({
    queryKey: ["pricing", "rules", filters],
    queryFn: () => api.get<PriceLineView[]>(`${V1}/admin/pricing/rules${query(filters)}`),
    enabled,
  });
}

export function useRuleHistory(line: { city: string; vehicleClass: VehicleClass; corporateId?: string } | null) {
  return useQuery({
    queryKey: ["pricing", "history", line],
    queryFn: () => api.get<HistoryEntryView[]>(`${V1}/admin/pricing/rules/history${query(line ?? {})}`),
    enabled: line !== null,
  });
}

function usePricingMutation<TVars, TResult>(fn: (vars: TVars) => Promise<TResult>) {
  const client = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => client.invalidateQueries({ queryKey: ["pricing"] }) });
}

export type RuleInput = {
  city: string;
  vehicleClass: VehicleClass;
  corporateId?: string;
  currency: string;
  baseMinor: number;
  perKmMinor: number;
  perMinMinor: number;
  minFareMinor: number;
  fuelIndexFactor: number;
  maxSurge: number;
  validFrom?: string;
  validTo?: string;
};

export function useCreateRule() {
  return usePricingMutation((input: RuleInput) => api.post<RuleView>(`${V1}/admin/pricing/rules`, input));
}

export function useRetireLine() {
  return usePricingMutation((input: { city: string; vehicleClass: VehicleClass; corporateId?: string; validFrom?: string }) =>
    api.post<RuleView>(`${V1}/admin/pricing/rules/retire`, input),
  );
}

export function useZones(city?: string) {
  return useQuery({
    queryKey: ["pricing", "zones", city ?? ""],
    queryFn: () => api.get<ZoneView[]>(`${V1}/admin/pricing/zones${query({ city })}`),
  });
}

export type ZoneInput = {
  city: string;
  type: ZoneType;
  nameKm?: string;
  nameEn?: string;
  polygon: LatLng[];
  surchargeMinor?: number;
  surchargeCurrency?: string;
  active: boolean;
};

export function useSaveZone() {
  return usePricingMutation(({ id, ...input }: ZoneInput & { id?: string; version?: number }) =>
    id ? api.put<ZoneView>(`${V1}/admin/pricing/zones/${id}`, input) : api.post<ZoneView>(`${V1}/admin/pricing/zones`, input),
  );
}

export function usePricingSettings() {
  return useQuery({ queryKey: ["pricing", "settings"], queryFn: () => api.get<SettingsView>(`${V1}/admin/pricing/settings`) });
}

export function useSetPricingSetting() {
  return usePricingMutation(({ setting, value }: { setting: "fuel-index" | "exchange-rate"; value: number }) =>
    api.put<SettingView>(`${V1}/admin/pricing/settings/${setting}`, { value }),
  );
}

export type QuoteLocation = { placeId?: string; latitude?: number; longitude?: number };

export function useCreateQuote() {
  return useMutation({
    mutationFn: (input: { pickup: QuoteLocation; dropoff: QuoteLocation; vehicleClass: VehicleClass; pickupAt?: string; corporateId?: string }) =>
      api.post<QuoteView>(`${V1}/quotes`, input),
  });
}
