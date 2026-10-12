"use client";

import { useFormatter, useLocale } from "next-intl";
import { useMemo } from "react";

const ZONE = "Asia/Phnom_Penh";
const KHMER_MONTHS = ["មករា", "កុម្ភៈ", "មីនា", "មេសា", "ឧសភា", "មិថុនា", "កក្កដា", "សីហា", "កញ្ញា", "តុលា", "វិច្ឆិកា", "ធ្នូ"];

/** Browsers ship no Khmer date data (Intl falls back to English), so Khmer dates are built here. */
function khmer(value: string, withTime: boolean) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-GB", {
      timeZone: ZONE,
      year: "numeric",
      month: "numeric",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(new Date(value))
      .map((p) => [p.type, p.value]),
  );
  const date = `${Number(parts.day)} ${KHMER_MONTHS[Number(parts.month) - 1]} ${parts.year}`;
  return withTime ? `${date} ម៉ោង ${parts.hour}:${parts.minute}` : date;
}

/** Dates are stored in UTC and shown in Phnom Penh time. A plain date (YYYY-MM-DD) is shown as is. */
export function useDates() {
  const format = useFormatter();
  const locale = useLocale();
  return useMemo(() => {
    const parse = (value: string) => (/^\d{4}-\d{2}-\d{2}$/.test(value) ? `${value}T12:00:00+07:00` : value);
    return {
      date: (value?: string | null) =>
        !value ? "—" : locale === "km" ? khmer(parse(value), false) : format.dateTime(new Date(parse(value)), { dateStyle: "medium" }),
      dateTime: (value?: string | null) =>
        !value
          ? "—"
          : locale === "km"
            ? khmer(value, true)
            : format.dateTime(new Date(value), { dateStyle: "medium", timeStyle: "short" }),
    };
  }, [format, locale]);
}

/** Value for <input type="datetime-local"> in the browser's time zone. */
export function toLocalInput(date: Date) {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** Days from today (Phnom Penh) until an ISO date. */
export function daysUntil(isoDate?: string | null) {
  if (!isoDate) return null;
  const today = new Intl.DateTimeFormat("en-CA", { timeZone: ZONE }).format(new Date());
  return Math.round((Date.parse(`${isoDate}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000);
}
