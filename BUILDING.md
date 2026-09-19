# Building MultiView for multiple Minecraft versions

MultiView is published as one jar per supported MC range. Supported range today:
**1.21.9 and newer, including MC 26.3**.

## MC 26.3 build notes

`versions/26.3.properties` targets MC 26.3 with Loom 1.17 + `noIntermediateMappings()`
(26.x ships unobfuscated — no intermediary/Yarn). Flashback 0.44.0-for-MC26.3 is a
local port with no Modrinth release, so provision it manually before building:

```
libs/
├── Flashback-0.44.0-for-MC26.3.jar   # build from the ewitulsk Flashback 26.3 port
└── dev-jij/                          # Flashback's JiJ companions for runClient
    ├── mixinconstraints-*.jar
    ├── mixinsquared-*.jar
    └── lattice-*.jar
```

With `libs/` populated, `./gradlew build` produces the 26.3 jar. Without it the
build still compiles but skips Flashback API bindings (a warning is logged).

## Mappings stack

The project compiles against **Mojang Mappings** (mojmap). At build time, Loom
fetches mojmap from the MC version's manifest and remaps the obfuscated jar
to the named namespace. The produced jar is shipped in the intermediary
namespace for Fabric Loader to load on production clients.

Why mojmap and not Yarn? Yarn ships per MC version and lags behind Mojang
releases. Mojmap is published by Mojang on the same day as every MC release,
so we can build for new versions immediately without waiting for the
FabricMC mapping volunteers.

## Layout

```
MultiView/
├── build.gradle                     # mojmap + Loom config (per-version Java toolchain)
├── gradle.properties                # active build config (swapped by build-version.sh)
├── versions/
│   ├── 1.21.9.properties            # MC 1.21.9 / 1.21.10
│   ├── 1.21.11.properties           # MC 1.21.11
│   ├── 26.1.properties              # MC 26.1.x
│   └── 26.3.properties              # MC 26.3 (current development target)
├── libs/                            # local Flashback jars per MC range (gitignored)
├── scripts/
│   ├── build-version.sh             # swaps properties + ./gradlew build
│   ├── fetch-flashback.sh           # downloads matching Flashback jars from Modrinth
│   └── test-all-versions.sh         # multi-version merge regression suite (headless)
└── src/test/java/.../MergeIntegrationTest.java
                                     # headless merge test driven by real multiplayer replays
```

## Testing

### Multi-version regression (recommended)

```bash
./scripts/test-all-versions.sh
```

Iterates over every `versions/*.properties`, swaps `gradle.properties`,
runs the headless `MergeIntegrationTest` against the four reference
multiplayer replays in `run/flashback/replays/`, and prints a summary
table with per-version stats (merged ticks, entity dedup count, LWW
overwrites, packets deduplicated).

A version PASSes when the merge produces a valid zip, all stats are
non-zero, and merge runs in full-fidelity mode (i.e. Bootstrap initialized
the registry correctly — degraded PASSTHROUGH mode fails the test).

Typical timing: ~5 minutes total for two MC versions on real data.

### Headless single-version

```bash
./gradlew test --tests MergeIntegrationTest
```

Runs the same JUnit test against the active `gradle.properties` setup.
Requires the four reference replay zips in `run/flashback/replays/`:

- `2026-02-20T23_25_15.zip`
- `Hika_Civ_4.zip`
- `Jour_4_Romani_.zip`
- `Sénat_empirenapo2026-02-20T23_20_16.zip`

If any source is missing, the test is skipped (`@EnabledIf`).

### Runtime client test

For verifying Flashback × MC playback (not just merge), the in-mod
`TestHarness` boots an actual MC client and exercises the full flow:

```bash
echo '{"mode":"merge","sources":[...],"output":"x","playSeconds":60}' > run/.multiview-test.json
./gradlew runClient
cat run/.multiview-test-result.json
```

Use this when changing client-side UI or testing Flashback API
compatibility on a new MC version. Slower (~5 min) than the headless
test but covers the playback engine.

## Building jars

```bash
# List versions known to the repo
./scripts/build-version.sh --list

# Build a specific version (writes build/libs/multiview-<modver>-mc<version>.jar)
./scripts/build-version.sh 1.21.11

# Build every supported version
./scripts/build-version.sh --all
```

The build script swaps `gradle.properties` for the version's overrides, runs
`./gradlew clean build`, then restores the original on exit. Each jar is
copied with a `-mc<version>` suffix so multiple builds co-exist in `build/libs/`.

## Fetching Flashback jars

```bash
./scripts/fetch-flashback.sh           # download every variant we configure
./scripts/fetch-flashback.sh 1.21.11   # one version
./scripts/fetch-flashback.sh --list    # show what's present
```

## Adding a new MC version

1. Create `versions/<mc>.properties` using `versions/1.21.11.properties` as a template.
2. Look up matching versions:
   - **Fabric API**: <https://modrinth.com/mod/fabric-api/versions>
   - **Flashback**: <https://modrinth.com/mod/flashback/versions>
3. Run `./scripts/fetch-flashback.sh <mc>` to populate `libs/`.
4. Run `./scripts/test-all-versions.sh` to confirm the merge still passes.
5. Run `./scripts/build-version.sh <mc>` to produce the jar.
6. Add the version to the matrix in `.github/workflows/multi-version-build.yml`.

## Publishing to Modrinth

Authentication via `MODRINTH_TOKEN` in `.env` (gitignored). Manual via `curl`
for now — see commit history for example calls. Project ID: `ja9dG9KW`.

## Status snapshot

| MC version       | Build | Headless test | Runtime test | Modrinth |
|------------------|-------|---------------|--------------|----------|
| 1.21 – 1.21.8    | dropped | n/a         | n/a          | not published |
| 1.21.9 / 1.21.10 | ✓     | ✓ PASS        | ✓ PASS (record+merge harness) | ✓ id `t9NqZjHK` |
| 1.21.11          | ✓     | ✓ PASS        | ✓ PASS (real multiplayer replays) | ✓ id `uXWEHvBV` |
| 26.1.x           | staged | untested    | untested     | not published |
| 26.3             | ✓     | ✓ PASS (real corpus) | ✓ PASS (hidden client: smoke/merge/mergeplay/ui) | not published |

### 26.x unblocked

The earlier "26.1+ blocked on Loom" issue is resolved: Loom **1.17-SNAPSHOT**
handles unobfuscated MC jars natively and `loom.noIntermediateMappings()` skips
the mappings declaration entirely (`no_intermediate=true` in the version
properties). The historical blocker text was removed — see git history.

### Hidden-client test framework (26.3+ only)

`src/main/java-testing` contains an in-process scripted client harness adapted
from Flashback's port: hidden SDL window, synthetic mouse input, per-frame
hidden/focus contract checks, and framebuffer-evidence screenshots. It is only
compiled for ≥26.3 targets (renderpearl/SDL3 APIs).

```powershell
# scenario: smoke | merge | mergeplay | merge8 | ui
scripts/Test-ClientSmoke.ps1 -Scenario merge `
    -ReplaySources "a.zip;b.zip"   # ';'-separated replay paths (merge* and ui need ≥2)
```

The runner exports the dev-client launch via `exportTestLaunches`, spawns it
windowless, validates `HIDDEN_*_PASS` log markers plus screenshot evidence, and
writes `result.json` under `artifacts/client-<scenario>-<ts>/`.
