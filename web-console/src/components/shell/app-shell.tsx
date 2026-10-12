"use client";

import { Languages, LogOut, UserRound } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarRail,
  SidebarTrigger,
} from "@/components/ui/sidebar";
import { Skeleton } from "@/components/ui/skeleton";
import { useMe } from "@/lib/api/hooks";
import { adminNav } from "./nav";
import { SessionProvider, useSession } from "./session";

export function AppShell({ children }: { children: React.ReactNode }) {
  const me = useMe();
  if (me.isPending) {
    return (
      <div className="flex min-h-svh items-center justify-center">
        <Skeleton className="h-8 w-48" />
      </div>
    );
  }
  if (me.isError || !me.data) {
    return null; // 401 redirects to /login in the API client
  }
  return (
    <SessionProvider me={me.data}>
      <SidebarProvider>
        <AppSidebar />
        {/* min-w-0: let the content shrink next to the sidebar (tablets); wide tables scroll inside it. */}
        <SidebarInset className="min-w-0">
          <TopBar />
          <main className="min-w-0 flex-1 p-4 md:p-6">{children}</main>
        </SidebarInset>
      </SidebarProvider>
    </SessionProvider>
  );
}

function AppSidebar() {
  const t = useTranslations();
  const pathname = usePathname();
  const session = useSession();
  // Most specific match wins, so /drivers/new does not also highlight /drivers.
  const active = adminNav
    .filter((item) => pathname === item.href || pathname.startsWith(item.href + "/"))
    .sort((a, b) => b.href.length - a.href.length)[0]?.href;
  return (
    <Sidebar collapsible="icon">
      <SidebarHeader>
        <div className="flex items-center gap-2 px-2 py-1.5 font-semibold group-data-[collapsible=icon]:hidden">
          {t("app.name")}
        </div>
      </SidebarHeader>
      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupLabel>{t("app.area.admin")}</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {adminNav
                .filter((item) => session.can(item.permission))
                .map((item) => (
                  <SidebarMenuItem key={item.href}>
                    <SidebarMenuButton
                      isActive={active === item.href}
                      tooltip={t(item.label)}
                      render={<Link href={item.href} />}
                    >
                      <item.icon />
                      <span>{t(item.label)}</span>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ))}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>
      <SidebarRail />
    </Sidebar>
  );
}

function TopBar() {
  const t = useTranslations();
  const locale = useLocale();
  const router = useRouter();
  const { me } = useSession();

  const switchLanguage = async (value: string) => {
    await fetch("/api/locale", { method: "POST", body: JSON.stringify({ locale: value }) });
    router.refresh();
  };
  const logout = async () => {
    await fetch("/api/auth/logout", { method: "POST" });
    // Full reload drops every cached query of the signed-out user.
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.assign("/login");
  };

  return (
    <header className="sticky top-0 z-10 flex h-14 items-center gap-2 border-b bg-background/95 px-3 backdrop-blur md:px-4">
      <SidebarTrigger aria-label={t("nav.collapse")} />
      <div className="flex-1" />
      <DropdownMenu>
        <DropdownMenuTrigger render={<Button variant="ghost" size="sm" aria-label={t("nav.language")} />}>
          <Languages />
          <span>{locale === "km" ? "ខ្មែរ" : "English"}</span>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuRadioGroup value={locale} onValueChange={(v) => switchLanguage(String(v))}>
            <DropdownMenuRadioItem value="km">ខ្មែរ</DropdownMenuRadioItem>
            <DropdownMenuRadioItem value="en">English</DropdownMenuRadioItem>
          </DropdownMenuRadioGroup>
        </DropdownMenuContent>
      </DropdownMenu>
      <DropdownMenu>
        <DropdownMenuTrigger render={<Button variant="ghost" size="sm" />}>
          <UserRound />
          <span className="max-w-40 truncate">{me.fullName || me.phone}</span>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-56">
          <DropdownMenuGroup>
            <DropdownMenuLabel className="leading-normal">
              <div className="font-medium">{me.fullName || me.phone}</div>
              <div className="text-xs font-normal text-muted-foreground">
                {(me.roles ?? []).map((r) => r.role).join(", ")}
              </div>
            </DropdownMenuLabel>
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={logout}>
            <LogOut />
            {t("nav.logout")}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </header>
  );
}
