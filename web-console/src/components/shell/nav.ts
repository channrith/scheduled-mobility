import { CarFront, FileCheck2, UserPlus, Users } from "lucide-react";
import type { Permission } from "@/lib/auth/roles";

/** Admin area navigation. Dispatcher console and corporate portal will add their own groups. */
export const adminNav: { href: string; label: string; icon: typeof Users; permission: Permission }[] = [
  { href: "/drivers", label: "nav.drivers", icon: Users, permission: "viewDrivers" },
  { href: "/drivers/new", label: "nav.registerDriver", icon: UserPlus, permission: "registerDrivers" },
  { href: "/documents/review", label: "nav.reviewQueue", icon: FileCheck2, permission: "reviewDocuments" },
  { href: "/vehicles", label: "nav.vehicles", icon: CarFront, permission: "viewVehicles" },
];
