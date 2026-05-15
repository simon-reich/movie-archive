# GSD Debug Knowledge Base

Resolved debug sessions. Used by `gsd-debugger` to surface known-pattern hypotheses at the start of new investigations.

---

## phase1-uat-failures — CSS variables hex vs HSL, missing auth guard, missing logout UI
- **Date:** 2026-05-16
- **Error patterns:** palette, colors, background, terracotta, hsl, css variables, hex, redirect, authenticated, login, logout, sign out, nav, navbar, single-use, consumed
- **Root cause:** (1) CSS custom properties used raw hex values but Tailwind wraps them in hsl() — invalid CSS, colors invisible. (2) Global middleware allowed all public routes unconditionally — no reverse guard for authenticated users. (3) Backend token single-use was correctly implemented — UAT false positive. (4) No navbar component in default layout — logout composable existed but had no UI entry point.
- **Fix:** Convert CSS vars to HSL space-separated components; add isAuthenticated redirect in middleware for public routes; create AppNav.vue with Sign out button and mount it conditionally in default.vue.
- **Files changed:** frontend/assets/css/main.css, frontend/middleware/auth.global.ts, frontend/layouts/default.vue, frontend/components/AppNav.vue, frontend/test/unit/middleware/auth.spec.ts
---

