import type { components } from "./schema";

type Schemas = components["schemas"];

/**
 * springdoc does not mark fields `required`, so generated types are all optional. The API always sends every
 * field (Jackson writes nulls), so models are treated as present; fields that can be null (vehicle,
 * homeZone, suspension, ...) are checked where they are used.
 */
type Present<T> = T extends (infer U)[] ? Present<U>[] : T extends object ? { [K in keyof T]-?: Present<NonNullable<T[K]>> } : T;

export type Model<N extends keyof Schemas> = Present<Schemas[N]>;

export type Me = Model<"Me">;
export type DriverSummary = Model<"DriverSummary">;
export type DriverDetail = Model<"DriverDetail">;
export type DocumentView = Model<"DocumentView">;
export type VehicleView = Model<"VehicleView">;
export type VehicleListItem = Model<"VehicleListItem">;
export type AssignmentView = Model<"AssignmentView">;
export type AuditView = Model<"AuditView">;
export type QueueItem = Model<"QueueItem">;
export type PreviewUrl = Model<"PreviewUrl">;

export type DriverStatus = NonNullable<Schemas["DriverSummary"]["status"]>;
export type HomeZone = NonNullable<Schemas["DriverSummary"]["homeZone"]>;
export type VehicleClass = NonNullable<Schemas["VehicleView"]["vehicleClass"]>;
export type VehicleOwnership = NonNullable<Schemas["VehicleView"]["ownership"]>;
export type VehicleStatus = NonNullable<Schemas["VehicleView"]["status"]>;
export type DocumentType = NonNullable<Schemas["DocumentView"]["type"]>;
export type DocumentStatus = NonNullable<Schemas["DocumentView"]["status"]>;

export type Page<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number };

export const DRIVER_STATUSES: DriverStatus[] = ["PENDING", "DOCS_SUBMITTED", "TRAINING", "APPROVED", "REJECTED", "SUSPENDED"];
export const HOME_ZONES: HomeZone[] = [
  "CHAMKAR_MON", "DAUN_PENH", "PRAMPIR_MAKARA", "TUOL_KOUK", "DANGKAO", "MEAN_CHEY", "RUSSEY_KEO",
  "SEN_SOK", "POR_SENCHEY", "CHROY_CHANGVAR", "PREK_PNOV", "CHBAR_AMPOV", "BOENG_KENG_KANG", "KAMBOUL",
];
export const VEHICLE_CLASSES: VehicleClass[] = ["MOTO", "TUKTUK_REMORK", "TUKTUK_RICKSHAW", "CAR", "SUV", "VAN"];
export const OWNERSHIPS: VehicleOwnership[] = ["DRIVER", "THIRD_PARTY", "COMPANY"];
export const PERSONAL_DOCUMENTS: DocumentType[] = ["NATIONAL_ID", "DRIVING_LICENSE", "PROFILE_PHOTO"];
export const TWO_SIDED: DocumentType[] = ["NATIONAL_ID", "DRIVING_LICENSE", "VEHICLE_REGISTRATION"];
export const EXPIRING: DocumentType[] = ["NATIONAL_ID", "DRIVING_LICENSE", "VEHICLE_REGISTRATION"];

export type AccountView = Model<"AccountView">;
export type AccountDetail = Model<"AccountDetail">;
export type MemberView = Model<"MemberView">;
export type MemberPolicy = Schemas["MemberPolicy"];
export type HoursWindow = NonNullable<Schemas["HoursWindow"]>;
export type CostCenterView = Model<"CostCenterView">;
export type PlaceView = Model<"PlaceView">;
export type PlaceHit = Model<"PlaceHit">;

export type AccountStatus = NonNullable<Schemas["AccountView"]["status"]>;
export type MemberRole = NonNullable<Schemas["MemberView"]["role"]>;
export type DayOfWeek = NonNullable<Schemas["HoursWindow"]["days"]>[number];
export type PlaceType = NonNullable<Schemas["PlaceView"]["type"]>;

export const CURRENCIES = ["USD", "KHR"] as const;
export type Currency = (typeof CURRENCIES)[number];
export const MEMBER_ROLES: MemberRole[] = ["CORPORATE_ADMIN", "CORPORATE_BOOKER", "RIDER"];
export const DAYS: DayOfWeek[] = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];
export const PLACE_TYPES: PlaceType[] = [
  "AIRPORT", "HOTEL", "MALL", "MARKET", "HOSPITAL", "SCHOOL", "EMBASSY", "OFFICE", "LANDMARK", "TRANSPORT", "OTHER",
];
