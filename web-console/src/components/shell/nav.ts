import { Building2, CarFront, Calculator, FileCheck2, Fuel, MapPin, Shapes, Tags, UserPlus, Users } from "lucide-react";
import type { Permission } from "@/lib/auth/roles";

export type NavItem = { href: string; label: string; icon: typeof Users; permission: Permission };

/** Admin area navigation. Dispatcher console and corporate portal will add their own groups. */
export const adminNav: NavItem[] = [
  { href: "/drivers", label: "nav.drivers", icon: Users, permission: "viewDrivers" },
  { href: "/drivers/new", label: "nav.registerDriver", icon: UserPlus, permission: "registerDrivers" },
  { href: "/documents/review", label: "nav.reviewQueue", icon: FileCheck2, permission: "reviewDocuments" },
  { href: "/vehicles", label: "nav.vehicles", icon: CarFront, permission: "viewVehicles" },
  { href: "/corporates", label: "nav.corporates", icon: Building2, permission: "viewCorporates" },
  { href: "/places", label: "nav.places", icon: MapPin, permission: "managePlaces" },
];

export const pricingNav: NavItem[] = [
  { href: "/pricing/rules", label: "nav.pricingRules", icon: Tags, permission: "viewPricing" },
  { href: "/pricing/zones", label: "nav.zones", icon: Shapes, permission: "viewPricing" },
  { href: "/pricing/settings", label: "nav.pricingSettings", icon: Fuel, permission: "viewPricing" },
  { href: "/pricing/quote-tester", label: "nav.quoteTester", icon: Calculator, permission: "viewPricing" },
];

/** Sidebar groups in order; a group is hidden when the user may open none of its items. */
export const navGroups: { label: string; items: NavItem[] }[] = [
  { label: "app.area.admin", items: adminNav },
  { label: "app.area.pricing", items: pricingNav },
];
