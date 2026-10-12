import { cookies } from "next/headers";
import { getRequestConfig } from "next-intl/server";
import { LOCALE_COOKIE, toLocale } from "./locales";

// No locale in URLs (internal tool): the language comes from a cookie set by the switcher; Khmer by default.
export default getRequestConfig(async () => {
  const locale = toLocale((await cookies()).get(LOCALE_COOKIE)?.value);
  return {
    locale,
    timeZone: "Asia/Phnom_Penh",
    messages: (await import(`../../messages/${locale}.json`)).default,
  };
});
