# Boomio mobile — branding and port plan

How `animaldev528/BoomioMobile` carries the Boomio brand on top of upstream
`NuvioMedia/NuvioMobile`, and what is still outstanding.

## Strategy: a flavor overlay, not a rebase

The TV fork settled this question first, and mobile copies the answer. A rebase is the wrong
tool: it rewrites the branch that upstream keeps moving, so every future upstream commit has
to be replayed by hand.

Instead:

- `boomio` is cut from `upstream/cmp-rewrite` and the brand lives in an **Android product
  flavor** plus a **per-flavor resource overlay**. Shared source under `main/` is never
  edited on this branch, so upstream merges stay cheap.
- CI merges upstream **forward** (`git merge`, never rebase) on a schedule. A merge it cannot
  do cleanly fails the run and opens an issue rather than silently rebuilding the old base.
- Feature work ports as cluster cherry-picks, following the method in the TV fork's
  `docs/boomio/PORT-PLAN.md`.

## Done on this branch

| Area | What changed |
| --- | --- |
| Product flavor | `boomio` added to the `distribution` dimension in `androidApp/build.gradle.kts`, with `applicationId = "com.boomio.mobile"` so it installs alongside the official app. |
| Manifest | `androidApp/src/boomio/AndroidManifest.xml` — `REQUEST_INSTALL_PACKAGES`, so the Boomio build self-updates from this fork's GitHub releases, matching `full`. |
| Signing | `NUVIO_RELEASE_*` now resolve **environment first**, then `local.properties`. CI injects the keystore without rewriting `local.properties`; upstream's own workflow is unaffected because it leaves the environment unset. |
| Debug id | `com.boomio.debug` instead of the flat `com.nuviodebug.com`, so Boomio debug and full debug can be installed side by side. |
| Icons | 21 files under `androidApp/src/boomio/res` — legacy, round, adaptive foreground, themed monochrome, and the splash logo, at the same pixel geometry as the upstream set they shadow. |
| Launcher label | `app_name` → "Boomio" in `values/` and `values-es/`. |
| CI | `.github/workflows/build-boomio.yml` — weekly Android build (below). |

## Outstanding

### 1. In-app brand name and strings

`app_name` only covers the launcher label, which is an Android resource. The in-app name is
`app_brand_name`, and it lives in **Compose Multiplatform Resources**
(`composeApp/src/commonMain/composeResources/values-*/strings.xml`, ~22 locales) generated
into `nuvio.composeapp.generated.resources`. An `androidApp` flavor overlay cannot shadow
generated Compose resources, so branding them requires a third `nuvio.android.distribution`
value that points composeApp at a `src/androidBoomio/` resource set.

Deliberately deferred to keep this branch's diff small. Until it lands, the app shows Nuvio's
name inside the UI.

### 2. Updater still points at Nuvio

`composeApp/src/commonMain/.../updater/AppUpdaterRepository.kt` hardcodes
`https://api.github.com/repos/NuvioMedia/NuvioMobile/...`, and the User-Agent `NuvioMobile`.

This is shared `commonMain`, so repointing it is not a one-line edit — it would leak into all
three flavors. It needs a flavor-aware seam, the way the TV fork exposes `GITHUB_OWNER` /
`GITHUB_REPO` as build config. Until then, a Boomio install would offer upstream Nuvio
releases as its own update.

### 3. Deep-link scheme — do not change blindly

The TV fork moved to `boomio://`. That was free there because TV's Trakt default is the
out-of-band `urn:ietf:wg:oauth:2.0:oob`, with no redirect URI registered anywhere.

Mobile is different: Trakt and Simkl default to `nuvio://auth/trakt` and `nuvio://auth/simkl`,
and those are **registered with the providers**. Changing the scheme without re-registering
both redirect URIs breaks sign-in. The scheme is therefore unchanged on this branch.

### 4. Which backend Boomio mobile talks to

The CI-supplied `local.properties` carries only third-party API keys
(`TMDB_API_KEY`, `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET`, `SIMKL_CLIENT_ID`). Every URL key
is excluded on purpose — same rule as TV — so no LAN host or private infrastructure name is
baked into a publicly downloadable APK.

The consequence to be aware of: with `NUVIO_SUPABASE_URL` / `NUVIO_SUPABASE_ANON_KEY` absent,
**cloud accounts and sync are inert** in the published APK. That is a product decision — point
Boomio mobile at the self-hosted plane, or keep it account-free — and it is not one this plan
should settle by default.

`SENTRY_DSN` is excluded for a different reason: it names Nuvio's Sentry project, so shipping
it would route Boomio crash reports into someone else's account.

### 5. Feature port

`origin/companion-bridge` holds the mobile Boomio work: **18 commits, 46 files
(+4574/−16), 24 added and 22 modified**, based on merge-base `6ceffbbe` — older than the
current upstream tip, so it cannot simply be merged.

The 24 added files apply cleanly; the 22 modified ones are the integration points and need
hand-merging. Not started.

## CI: `.github/workflows/build-boomio.yml`

Weekly (Mondays 06:23 UTC), Android only, plus `workflow_dispatch` for ad-hoc runs.

There is no upstream stable tag to track — NuvioMobile's releases are all `-beta` and are not
flagged as pre-releases, so `releases/latest` would resolve to whatever shipped that morning.
The workflow tracks the upstream **branch** instead, and `upstream_ref` pins to a tag when you
want to freeze.

Flow: merge upstream forward → push → read the version from
`iosApp/Configuration/Version.xcconfig` → skip if that version is already released → build →
publish a fork release tagged `boomio-<versionName>-<versionCode>` (currently
`boomio-0.5.3-135`), skipping duplicates so quiet weeks produce nothing.

Two mobile-specific details worth remembering:

- **`-Pnuvio.android.distribution=full` is mandatory.** `composeApp/build.gradle.kts` derives
  the distribution from the task name, and `assembleBoomioRelease` contains neither `full` nor
  `playstore`, so the task reads as ambiguous and a `require` hard-fails the build without the
  flag. `boomio` reuses the `full` composeApp distribution.
- **`TRAKT_CLIENT_ID` / `SIMKL_CLIENT_ID` have no environment fallback** — they are compiled
  into `BuildConfig` straight from `local.properties`. An absent key does not fail the build;
  it ships an APK whose login buttons cannot work. The workflow therefore validates them.

The schedule fires from the repository's **default branch**, which is why the fork's default
is `boomio` rather than the `cmp-rewrite` mirror. `setup-secrets-mobile.sh` sets that up along
with the secrets.

## Verification status

Nothing here has been compiled. Building on the dev machine is against the project's rules, so
**the first compile is the CI run** — dispatch it once with `mode=dry-run` before trusting the
weekly cron.
