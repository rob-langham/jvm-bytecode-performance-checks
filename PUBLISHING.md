# Publishing

The build is fully configured. What remains is account setup that only you can do — namespace
verification, a signing key, and four sets of credentials.

Nothing here has been published yet.

## Coordinates

| | |
| --- | --- |
| Group | `io.github.rob-langham` |
| Modules | `core`, `gradle-plugin`, `maven-plugin` |
| Gradle plugin id | `io.github.rob-langham.static-allocation-checker` |
| Version | `0.1.0` |

Java packages stay `com.staticallocationchecker.*`. A group and a package name do not have to
match, and renaming would mean editing the annotation descriptors the checker matches on
(`Lcom/staticallocationchecker/annotations/ZeroAllocations;`) and the agent's `Premain-Class` —
places where a typo fails silently at runtime rather than at compile time.

### Why not `com.staticallocationchecker`

Both Maven Central and the Gradle Plugin Portal require proof that you control the namespace.
`com.staticallocationchecker` would need ownership of `staticallocationchecker.com`. `io.github.*`
is verified from the GitHub account itself, free and immediate.

---

## One-time setup

### 1. Maven Central namespace

1. Sign in at [central.sonatype.com](https://central.sonatype.com) with GitHub.
2. **Namespaces → Add Namespace** → `io.github.rob-langham`.
3. It will ask you to create a public repository with a generated name to prove account ownership.
   Create it, click verify, delete it.

### 2. A signing key

Central rejects unsigned artifacts.

```bash
gpg --quick-generate-key "Robert Langham <robert.s.langham@gmail.com>" rsa4096 sign 2y
gpg --list-secret-keys --keyid-format=long          # note the key id

# Publish the public half, or Central cannot verify the signatures
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# The private half, for the CI secret
gpg --armor --export-secret-keys <KEY_ID>
```

Keep the private key out of the repository. It goes in a GitHub secret and nowhere else.

### 3. Credentials

**Central Portal** — Account → *Generate User Token*. Gives a username and password pair.

**Gradle Plugin Portal** — separate site, separate account:

1. Register at [plugins.gradle.org](https://plugins.gradle.org/user/register).
2. Under *API Keys*, generate a key and secret.
3. The first publication of a new plugin id is **manually reviewed**, which takes a day or two.
   Expect the first release to sit pending; later ones are immediate.

### 4. Repository secrets

Settings → Secrets and variables → Actions:

| Secret | From |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central user token |
| `MAVEN_CENTRAL_PASSWORD` | Central user token |
| `SIGNING_KEY` | `gpg --armor --export-secret-keys` output, whole block |
| `SIGNING_PASSWORD` | The key's passphrase |
| `GRADLE_PUBLISH_KEY` | Plugin Portal API key |
| `GRADLE_PUBLISH_SECRET` | Plugin Portal API secret |

---

## Releasing

```bash
git tag v0.1.0
git push origin v0.1.0
```

The `release` workflow builds, tests, checks the tag matches the project version, then publishes.

**The Central upload is staged, not released.** Go to
[central.sonatype.com](https://central.sonatype.com) → Deployments, check the contents, and click
publish. That deliberate step exists because **a Central release is irreversible** — a version
number can never be reused or withdrawn, so a mistake is permanent. One click is cheap insurance.

To rehearse without publishing: Actions → release → *Run workflow*, leaving **dryRun** ticked.

## Verifying locally

```bash
./gradlew publishToMavenLocal
find ~/.m2/repository/io/github/rob-langham -type f
```

This has been run, and produces for each module a jar, sources, javadoc, a POM carrying every field
Central requires, plus the plugin marker and the shaded `-agent` jar. Signing is skipped when no
key is configured, so this works without a GPG setup.

## After the first release

Consumers replace the `includeBuild` lines in `demo/settings.gradle.kts` with nothing at all — the
coordinates then resolve normally:

```kotlin
plugins {
    id("io.github.rob-langham.static-allocation-checker") version "0.1.0"
}

dependencies {
    implementation("io.github.rob-langham:core:0.1.0")
}
```

Then bump `version` in the root `build.gradle.kts` to `0.2.0` so `main` is never sitting on an
already-published version.
