export const locales = ["km", "en"] as const;
export type Locale = (typeof locales)[number];
export const defaultLocale: Locale = "km";
export const LOCALE_COOKIE = "NEXT_LOCALE";

export function toLocale(value: string | undefined | null): Locale {
  return (locales as readonly string[]).includes(value ?? "") ? (value as Locale) : defaultLocale;
}
