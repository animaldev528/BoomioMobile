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
| Launcher label | `app_name` → "Boomio" in `values/`, `values-es/` and `values-bn/`. Every qualifier the library defines needs its own file: resources resolve per qualifier, so `values-bn/` was needed or a Bengali-locale device read "Nuvio". |
| In-app wordmark | 8 PNGs under `androidApp/src/boomio/assets/composeResources/…/drawable/` — the Boomio wordmark in all 7 theme tints plus the default. See below. |
| Updater | Repointed from `NuvioMedia/NuvioMobile` to `animaldev528/BoomioMobile`. |
| CI | `.github/workflows/build-boomio.yml` — weekly Android build (below). |

## Outstanding

### 1. In-app brand name and strings — wordmark DONE, strings open

The visible brand inside the UI is not a string: it is the wordmark **image**, drawn by
`AppBrandWordmark` via `painterResource` over `app_logo_wordmark[_<theme>].png` (7 tints +
default). `app_brand_name` ("Nuvio", ~22 locale files) is only that image's accessibility
`contentDescription`.

Compose Multiplatform packages those images at
`assets/composeResources/<pkg>/drawable/` inside the APK. An app flavor's assets are merged
**above** the library's, so the same path under `androidApp/src/boomio/assets/` shadows the
copy inside `composeApp` — no third `nuvio.android.distribution` value is needed. That claim
is **verified, not assumed**: `aapt`-extracting all 8 packaged wordmarks from a real
`assembleBoomioRelease` APK gives an alpha channel identical to the Boomio master
(`9ada899cf20c`) in 8/8 files, with Nuvio's alphas (`4ef4a378182a`, `a9f67ee52ff0`) absent.
Alpha is the discriminator because the letterforms differ geometrically; PNG bytes do not
compare, since the build recompresses them.

Regenerate with `docs/boomio/branding/gen-wordmarks.py` (needs PIL). Do not move it to
`tools/` — upstream's `.gitignore` excludes that directory.

Still open: ~47 ordinary UI strings that mention "Nuvio" (`settings_licenses_*` and friends).
These are plain Compose string resources, shadowed by the same asset path
(`values*/strings.cvr`). Not started deliberately — several name real third-party things
where renaming would be wrong, so the set needs picking over rather than a blanket sed.

**Some of these MUST NOT change, and upstream is GPL-3.0.** `LicensesAttributionsPage.kt`
carries `NuvioRepositoryUrl = "https://github.com/NuvioMedia/NuvioMobile"`, and GPL-3 §5
obliges a modified version to preserve the original's attribution. That link, the
`settings_licenses_attributions_*` bodies and `settings_licenses_attributions_nuvio_title`
are licence notices, not branding. They are also the only remaining `NuvioMedia/NuvioMobile`
reference in the shipped dex — its presence there is **correct**, and a grep that flags it as
a leak is misreading it. Do not "clean it up".

Also leave alone, because they name something real rather than the app itself:
`compose_auth_link_open` (`nuvio.tv/link` — the live device-authorisation page),
`server_error_official_*` (`api.nuvio.tv`, the actual official server),
`profile_background_member_note` (the Nuvio web panel), and the `community_*` /
`*_membership_*` strings (Nuvio's Patreon). The genuinely safe set is the app referring to
itself — `app_brand_name`, `addons_appstore_empty_title`, `action_support_nuvio`,
`settings_notifications_*`, `details_servers_unreachable`, `companion_*`, and similar.

**Mechanism caveat before doing this work:** a flavor asset overlay shadows the whole
`values*/strings.cvr` file, it does not merge per key. An override therefore has to carry the
complete locale file and goes stale the moment upstream adds a string — which would silently
drop it. This wants a regeneration script (derive the overlay from upstream's current `.cvr`,
apply a substitution map) rather than a checked-in copy, the same shape as
`docs/boomio/branding/gen-wordmarks.py`. Not verified yet whether Compose falls back to the
library's copy for keys absent from the shadowing file; confirm before relying on it.

### 2. Updater — DONE

Was hardcoded to `NuvioMedia/NuvioMobile`. Repointed to `animaldev528/BoomioMobile`, so a
Boomio install updates from this fork instead of offering Nuvio's releases as its own.

The related trap is the release **tag format**: `VersionUtils.parse` is an anchored semver
regex and `ReleaseSelector` drops anything it cannot parse, silently. The CI therefore tags
bare `X.Y.Z`; a decorated tag such as `boomio-0.5.3-135` would make every release invisible
to the updater with no error surfaced anywhere.

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

### Fork hygiene that PRs depend on

Two things, both handled by `setup-secrets-mobile.sh`:

- The default branch must be `boomio`. A `schedule` only fires from the default branch, and
  `workflow_dispatch` likewise needs the file present there before it can be run at all.
- `discontinue-legacy-main.yml` is **removed** on this branch. It triggers on
  `pull_request_target: [opened, reopened]` and closes every PR on the repository with a "the
  legacy app is being discontinued" comment — upstream's wind-down for the retired React
  Native codebase, which on a product fork means every PR dies seconds after it opens. That is
  exactly what happened to the branding PR.

  Because `pull_request_target` reads the workflow from the PR's **base** branch, deleting it
  here only helps once `boomio` is also the default branch (so new PRs base against it rather
  than against `cmp-rewrite`). Reinstating it is a one-file revert.

There is deliberately **no PR for the branding work itself**: `boomio` is the product branch,
so a PR would need a base other than itself and would show every upstream commit as a
difference against the stale `cmp-rewrite` mirror. The branch is the artifact. PRs are for the
port clusters, which land *into* `boomio`.

## Verification status

Nothing here has been compiled. Building on the dev machine is against the project's rules, so
**the first compile is the CI run** — dispatch it once with `mode=dry-run` before trusting the
weekly cron.
