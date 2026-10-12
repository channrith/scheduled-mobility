# web-console

Operations console (Next.js 16, App Router). Today: driver onboarding (admin). Later: dispatcher console
and corporate portal as their own route groups.

```bash
cp .env.example .env.local     # CORE_API_URL, default http://localhost:8080
npm install
npm run dev                    # http://localhost:3000
```

Sign in with a staff phone number (ADMIN, DISPATCHER, SUPPORT or SAFETY_OFFICER). With core-app's `local`
profile the code is printed in the core-app log (`SMS to …`).

| Script | |
|---|---|
| `npm run lint` · `npm run typecheck` · `npm run build` | What CI runs |
| `npm run gen:api` | Regenerate `src/lib/api/schema.ts` from `../docs/openapi.json` after an API change |

## How it is put together

- `src/app/(auth)`, `src/app/(admin)`: one route group per area, each with its own layout. Add
  `(dispatch)` and `(corporate)` the same way.
- `src/app/api/core/[...path]`: backend-for-frontend proxy to core-app. Access and refresh tokens live in
  httpOnly cookies scoped to `/api`; the browser never sees them. On 401 the proxy refreshes once; parallel
  refreshes share one Idempotency-Key so core-app rotates the token only once.
- `src/lib/api`: generated types, fetch client (adds an Idempotency-Key to every mutation) and TanStack
  Query hooks. `src/lib/auth/roles.ts`: what each role sees (the API enforces the same rules).
- `src/features/*`: drivers, documents, vehicles. `src/components/ui`: shadcn/ui (Base UI).
- i18n: next-intl, `messages/km.json` (default) and `messages/en.json`; language stored in a cookie.
- Khmer typography: Kantumruy Pro, line-height ≥ 1.65, no fixed small heights with clipping on badges,
  inputs and buttons.
