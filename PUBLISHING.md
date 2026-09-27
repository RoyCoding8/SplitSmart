# Publishing SplitSmart

How to build, sign, release, and submit SplitSmart to distribution channels.

## Requirements

- JDK 17
- Android SDK with platform 35 and build-tools 35

Set `ANDROID_HOME`, or put `sdk.dir=/path/to/sdk` in `local.properties`. That file is git-ignored.

## Build

```sh
./gradlew :app:assembleDebug      # debug APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest  # unit suite, must be green before any release
./gradlew :app:assembleRelease    # release APK, unsigned without the properties below
```

## Release signing

The release build type reads four Gradle properties. When all four are present the APK is signed. When they are absent the signing config is not registered at all, so `assembleRelease` produces an unsigned APK rather than failing. That second path is what lets F-Droid build from source without a keystore.

| Property | Meaning |
|---|---|
| `SPLITSMART_STORE_FILE` | Path to the keystore file |
| `SPLITSMART_STORE_PASSWORD` | Keystore password |
| `SPLITSMART_KEY_ALIAS` | Key alias |
| `SPLITSMART_KEY_PASSWORD` | Key password |

Pass them as `ORG_GRADLE_PROJECT_*` environment variables or as `-P` flags. Never commit a keystore or a `key.properties` file. Both are already covered by `.gitignore`.

The keystore and its backup are the app's identity. Android will refuse an update signed by a different key, so losing them means every existing user has to uninstall and reinstall.

**PKCS12 constraint.** The keystore is `PKCS12`, which cannot hold a key
password different from the store password. `keytool -genkeypair` accepts
`-keypass` and reports success, then silently discards a value that differs.
Gradle later fails with `Given final block not properly padded`. Generate one
password and use it for both `SPLITSMART_STORE_PASSWORD` and
`SPLITSMART_KEY_PASSWORD`.

## GitHub Releases

Pushing a tag matching `v*` runs the release job in `.github/workflows/ci.yml`. It runs the unit suite, assembles a signed release APK, and attaches it to a GitHub Release.

Set four repository secrets first:

- `SPLITSMART_STORE_FILE` — the keystore, base64-encoded. Produce it with `base64 -w0 keystore.jks`
- `SPLITSMART_STORE_PASSWORD`
- `SPLITSMART_KEY_ALIAS`
- `SPLITSMART_KEY_PASSWORD`

Then tag and push:

```sh
git tag v1.0.0
git push origin v1.0.0
```

Keep `versionName` in `app/build.gradle.kts` and the git tag in step. F-Droid reads the tag to discover new versions.

## F-Droid

SplitSmart has no proprietary dependencies and no network code at all — the manifest
declares no `INTERNET` permission. Settling up hands off to a payment app the user
picks via a normal `ACTION_VIEW` intent; no payment app is bundled or depended on, so
nothing needs declaring as a dependency. That makes it a strong candidate for the
official F-Droid repository.

### Metadata

Descriptions live in `fastlane/metadata/android/en-US/`, following the fastlane layout that F-Droid reads directly from the repository.

- `title.txt`
- `short_description.txt` — under 80 characters, no trailing period
- `full_description.txt`
- `images/icon.png` — 512x512
- `images/phoneScreenshots/*.png`
- `changelogs/<versionCode>.txt` — under 500 characters

### Submission

F-Droid builds from tagged source rather than accepting an uploaded APK. Each app is one metadata file in the `metadata/` directory of their data repository, named after the application id. Fork the repository, add the file on a branch, and open a merge request.

```sh
git clone https://gitlab.com/YOUR_USERNAME/fdroiddata.git
cd fdroiddata
git checkout -b splitsmart
mkdir -p metadata
```

Create `metadata/com.splitsmart.yml`:

```yaml
Categories:
  - Finance Manager
License: Apache-2.0
AuthorName: RoyCoding8
SourceCode: https://github.com/RoyCoding8/SplitSmart
Changelog: https://github.com/RoyCoding8/SplitSmart/blob/main/CHANGELOG.md
IssueTracker: https://github.com/RoyCoding8/SplitSmart/issues

RepoType: git
Repo: https://github.com/RoyCoding8/SplitSmart

Builds:
  - versionName: '1.0.0'
    versionCode: 1
    # Full 40-character commit hash from `git rev-parse HEAD`, not a tag.
    commit: 0000000000000000000000000000000000000000
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: '1.0.0'
CurrentVersionCode: 1
```

`gradle: [yes]` tells F-Droid to run `assembleRelease` with no product flavors, which matches the single `:app` module. `UpdateCheckMode: Tags` makes F-Droid look for a new `v*` tag on every check, so a later release is picked up without editing this file.

`commit:` takes the full 40-character hash, not a tag and not a branch. Re-read it with `git rev-parse HEAD` when you open the merge request, since any new commit invalidates it.

Push and open a merge request against `fdroid/fdroiddata`:

```sh
git add metadata/com.splitsmart.yml
git commit -m "Add SplitSmart"
git push origin splitsmart
```

First-time contributors must open a Request for Packaging issue before the merge request. Do that first, at `gitlab.com/fdroid/rfp/-/issues`, and mention the intended application id.

### Verifying the build locally

F-Droid's maintainers build in a Docker container. Reproducing it locally catches build problems before the merge request does, and it is the same check they run.

```sh
sudo docker run --rm -itu vagrant --entrypoint /bin/bash \
  -v ~/fdroiddata:/build:z \
  -v ~/fdroidserver:/home/vagrant/fdroidserver:Z \
  registry.gitlab.com/fdroid/fdroidserver:buildserver
```

Inside the container:

```sh
export PATH="$fdroidserver:$PATH" PYTHONPATH="$fdroidserver"
cd /build
fdroid readmeta
fdroid rewritemeta com.splitsmart
fdroid checkupdates --allow-dirty com.splitsmart
fdroid lint com.splitsmart
fdroid build com.splitsmart
```

If `readmeta` or `build` reports an error, fix the YAML rather than the app. A green `fdroid build` here is strong evidence the merge request will succeed.

### Signing

F-Droid generates and holds a signing key per app, then reuses it across versions so updates install normally. The developer keystore stays local and is never uploaded.

F-Droid can optionally verify that its build matches a developer-signed APK byte-for-byte, ignoring the signature, and publish the developer's signature instead. That is a separate opt-in and is not required for inclusion.

## Other channels

- **F-Droid** — the official F-Droid repository, described above.
- **GitHub Releases** — a signed APK attached to each tag, described above.
- **Direct APK** — `./gradlew :app:assembleRelease` produces one you can distribute yourself. Users cannot update from a GitHub-signed build to an F-Droid-signed build without reinstalling, so pick a primary channel early.
