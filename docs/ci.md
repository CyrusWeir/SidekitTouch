# Continuous integration

Sidekit runs its checks on GitHub Actions. All jobs are defined in
[`.github/workflows`](../.github/workflows) and share the JDK 21, Android SDK 37 and
Gradle cache setup in [`.github/actions/android-setup`](../.github/actions/android-setup).

## What runs

| Job | Command | Catches |
| --- | --- | --- |
| Gradle wrapper validation | `gradle/actions/wrapper-validation` | A modified or unverified `gradle-wrapper.jar` |
| Build | `./gradlew :app:assembleDebug :app:assembleRelease :sidekit:assembleDebug` | Compile errors and R8/keep-rule breakage that only release builds show |
| Unit tests | `./gradlew testDebugUnitTest` | JVM, Robolectric and Compose tests in both modules |
| Android Lint | `./gradlew lintDebug` | API-level, manifest and resource problems; uploads SARIF to code scanning |
| Kotlin formatting | `./gradlew spotlessCheck` | ktlint violations in files changed since `main` |
| Secret scan | `gitleaks/gitleaks-action` | Committed credentials, private keys and tokens |
| Dependency review | `actions/dependency-review-action` | High-severity vulnerable dependencies in a pull request |
| CodeQL | manual Gradle build + `codeql-action` | Security data-flow issues in Kotlin and Java (`codeql.yml`) |
| Dependency graph | `gradle/actions/dependency-submission` | Keeps the graph that dependency review reads up to date (`dependency-graph.yml`) |

These checks gate pull requests. The repository does not run instrumented tests on
an emulator: the only `androidTest` source today is the generated example, and the
[PR template](../.github/pull_request_template.md) already requires physical-device
testing for app changes. Add a `connectedDebugAndroidTest` job once real
instrumentation tests exist.

## Formatting

Formatting is checked with ktlint through Spotless. It is *ratcheted* against
`origin/main`, so only files changed on a branch must be clean. This lets the
project adopt formatting without rewriting untouched code.

```bash
./gradlew spotlessCheck   # report violations
./gradlew spotlessApply   # format the changed files
```

The rules live in [`.editorconfig`](../.editorconfig). `ktlint_code_style =
android_studio` matches Android Studio's defaults, `max_line_length = off` keeps the
existing long-line style, and `no-wildcard-imports` is disabled because a few MMS
and WorkManager adapters intentionally import whole packages.

ktlint caches the parsed `.editorconfig` inside the Gradle daemon. After editing it,
run `./gradlew --stop` before the next `spotlessApply` or `spotlessCheck`.

CI checks out the full history so the `origin/main` ref that Spotless ratchets
against exists. Locally, fetch `main` first if the check complains about a missing
ref.

## Running the pipeline locally

```bash
./gradlew :app:assembleDebug :app:assembleRelease :sidekit:assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew spotlessCheck
```

Reports are written to `app/build/reports/` and `sidekit/build/reports/`.

## Branch protection

Recommended settings for `main`:

- Require a pull request before merging.
- Require the status checks `Gradle wrapper validation`, `Build (debug + R8 release)`,
  `Unit tests`, `Android Lint`, `Kotlin formatting`, and `Secret scan`.
- Require branches to be up to date before merging.
- Do not allow bypassing the above settings.

`Dependency review` only runs on pull requests, and `CodeQL` runs on pushes and
pull requests to `main`; keep them optional if they are still being tuned.

## Things that are intentionally not in PR CI

- **Network integration probes** (`scripts/verify_nsw_transit.py`) depend on a
  third-party service and should run on a schedule or manually, not block a pull
  request.
- **Emulator tests**: see above.
- **Release signing and publishing**: wire this up when there is a distribution
  channel. The `release` build type is currently unsigned, so CI only verifies
  that R8 succeeds.
