"use client";

import { createContext, useContext } from "react";
import type { Me } from "@/lib/api/types";
import { can, type Permission } from "@/lib/auth/roles";

type Session = { me: Me; roles: string[]; can: (permission: Permission) => boolean };

const SessionContext = createContext<Session | null>(null);

export function SessionProvider({ me, children }: { me: Me; children: React.ReactNode }) {
  const roles = (me.roles ?? []).map((r) => r.role ?? "");
  return <SessionContext value={{ me, roles, can: (p) => can(roles, p) }}>{children}</SessionContext>;
}

export function useSession() {
  const session = useContext(SessionContext);
  if (!session) throw new Error("useSession outside SessionProvider");
  return session;
}

/** Renders children only for users with the permission; otherwise a short notice (pages) or nothing. */
export function RequirePermission({
  permission,
  fallback = null,
  children,
}: {
  permission: Permission;
  fallback?: React.ReactNode;
  children: React.ReactNode;
}) {
  return useSession().can(permission) ? <>{children}</> : <>{fallback}</>;
}
