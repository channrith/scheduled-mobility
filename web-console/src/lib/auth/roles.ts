export type Role =
  | "PASSENGER"
  | "DRIVER"
  | "CORPORATE_ADMIN"
  | "CORPORATE_BOOKER"
  | "DISPATCHER"
  | "SUPPORT"
  | "SAFETY_OFFICER"
  | "ADMIN";

/** Roles that may use the console. */
export const STAFF_ROLES = ["ADMIN", "DISPATCHER", "SUPPORT", "SAFETY_OFFICER"] as const;

/**
 * What each role may do in the console. Mirrors the backend's @PreAuthorize rules, which remain the
 * real enforcement; this only decides what to show.
 */
export const permissions = {
  viewDrivers: ["ADMIN", "DISPATCHER", "SUPPORT", "SAFETY_OFFICER"],
  registerDrivers: ["ADMIN"],
  editDrivers: ["ADMIN"],
  onboardingActions: ["ADMIN"],
  suspend: ["ADMIN", "SAFETY_OFFICER"],
  reinstate: ["ADMIN"],
  reviewDocuments: ["ADMIN"],
  viewDocumentFiles: ["ADMIN", "SAFETY_OFFICER"],
  viewAudit: ["ADMIN", "SAFETY_OFFICER"],
  viewVehicles: ["ADMIN", "DISPATCHER", "SUPPORT", "SAFETY_OFFICER"],
  manageVehicles: ["ADMIN"],
  viewCorporates: ["ADMIN", "DISPATCHER", "SUPPORT", "SAFETY_OFFICER"],
  manageCorporates: ["ADMIN"],
  managePlaces: ["ADMIN", "DISPATCHER"],
  mergePlaces: ["ADMIN"],
  viewPricing: ["ADMIN", "DISPATCHER", "SUPPORT"],
  managePricing: ["ADMIN"],
} as const satisfies Record<string, readonly Role[]>;

export type Permission = keyof typeof permissions;

export function can(roles: readonly string[], permission: Permission): boolean {
  return (permissions[permission] as readonly string[]).some((r) => roles.includes(r));
}
