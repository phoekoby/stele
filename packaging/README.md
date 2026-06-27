# Packaging & distribution

Two separate layers — don't conflate them:

| Layer | Tool | What you get |
|---|---|---|
| **Build the artifact** | Gradle | `installDist` (dir + launcher) · **`shadowJar` → one `stele.jar`** · `distZip` |
| **Deliver it** | Homebrew / Scoop / npm / install script | a user-facing `brew install …` etc. |

Gradle does **not** put things on a user's PATH — that's the job of the package manager (brew/scoop)
or the `install.sh` / `install.ps1` scripts. Keep build and system-install separate.

## The artifact

```bash
./gradlew :cli:shadowJar      # -> cli/build/libs/stele.jar  (~47 MB, all deps + native libs bundled)
java -jar stele.jar --help
```

This is a single runnable file but **still needs a JVM (Java 17+)** — it is a fat jar, not a native
binary. Every channel below is a thin wrapper that runs `java -jar stele.jar`, so they declare a Java
dependency.

## Publish, then the one-liners light up

1. Tag a release on GitHub and attach `stele.jar` (+ its `sha256`).
2. Point the templates here at that release URL + hash. Then:

- **Homebrew** (`homebrew/stele.rb`): put the formula in a tap repo `homebrew-stele`, then
  `brew install <you>/stele/stele`. Declares `depends_on "openjdk"`.
- **Scoop** (Windows, `scoop/stele.json`): `scoop install <bucket>/stele`. Drops a `stele.cmd` shim.
- **npx / npm** (`npm/`): `npx stele-cli …` — a tiny launcher that downloads the jar once and runs it
  (requires `java` on PATH).
- **Curl/irm install scripts**: `install.sh` / `install.ps1` already build-from-source; for a download
  install add a 3-line bootstrap that fetches `stele.jar` from the release instead of building.

## "I want a real binary, no Java at all"

Two routes, both heavier:

- **jpackage** (JDK built-in): bundles a trimmed JRE into a native installer (`.msi` / `.dmg` / `.deb`) or
  app-image. No Java needed by the user, but the artifact is per-OS (~40 MB) and you build one per platform.
- **GraalVM native-image**: a true static binary, no JVM. The blocker is this stack's native/reflection
  use — **java-tree-sitter (JNI)**, **sqlite-jdbc**, and especially **Liquibase** (heavy reflection +
  service loaders + XML) need reachability/JNI/resource config. Doable but a real project, not a flag.

Recommendation: ship `stele.jar` + a Homebrew formula (`depends_on openjdk`) and Scoop manifest now;
treat the no-Java native binary as a later milestone.
