/** Money is sent and stored in minor units: USD cents, KHR riel (no fraction). */
const DIGITS: Record<string, number> = { USD: 2, KHR: 0 };

export function fractionDigits(currency: string) {
  return DIGITS[currency] ?? 2;
}

/** "12.50" + USD → 1250; returns NaN for anything that is not a plain amount. */
export function toMinor(amount: string, currency: string) {
  const digits = fractionDigits(currency);
  const pattern = digits === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${digits}})?$`);
  const clean = amount.replace(/[,\s]/g, "");
  if (!pattern.test(clean)) return Number.NaN;
  return Math.round(Number(clean) * 10 ** digits);
}

/** 1250 + USD → "12.50" (for inputs). */
export function fromMinor(minor: number, currency: string) {
  const digits = fractionDigits(currency);
  return (minor / 10 ** digits).toFixed(digits);
}

/** 1250 + USD → "$12.50"; 40000 + KHR → "40,000 ៛". */
export function formatMoney(minor: number | null | undefined, currency: string | null | undefined) {
  if (minor === null || minor === undefined || !currency) return "—";
  const digits = fractionDigits(currency);
  const amount = new Intl.NumberFormat("en-US", { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(
    minor / 10 ** digits,
  );
  return currency === "USD" ? `$${amount}` : currency === "KHR" ? `${amount} ៛` : `${amount} ${currency}`;
}
