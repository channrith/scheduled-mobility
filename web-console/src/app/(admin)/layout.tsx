import { AppShell } from "@/components/shell/app-shell";

/** Operations area. Future areas get their own route groups: (dispatch), (corporate). */
export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return <AppShell>{children}</AppShell>;
}
