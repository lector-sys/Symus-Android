#!/usr/bin/env bash
# Publish a new version to every installed copy.
#
#   scripts/release.sh 0.2.0 "What changed"
#
# Bumps versionName/versionCode, builds both flavours signed with the release
# key, commits, tags v<version>, pushes, and creates the GitHub release the app
# checks on launch. Installed copies see it the next time they start.
set -euo pipefail
cd "$(dirname "$0")/.."

version="${1:?usage: scripts/release.sh <version> [notes]}"
notes="${2:-SYmuS $version}"
gradle_file=app/build.gradle.kts

[ -f ../symus-release-keys/keystore.properties ] || { echo "release key missing: ../symus-release-keys" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "commit your changes first" >&2; exit 1; }

code=$(( $(sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' "$gradle_file") + 1 ))
sed -i "s/^\( *versionCode = \)[0-9]*/\1$code/; s/^\( *versionName = \)\"[^\"]*\"/\1\"$version\"/" "$gradle_file"

./gradlew --no-daemon testFullDebugUnitTest testLiteDebugUnitTest assembleFullRelease assembleLiteRelease

out=app/build/outputs/apk
mkdir -p build/release
cp "$out/full/release/app-full-release.apk" build/release/symus-full.apk
cp "$out/lite/release/app-lite-release.apk" build/release/symus-lite.apk

git commit -am "Release $version"
git tag "v$version"
git push origin HEAD "v$version"
gh release create "v$version" build/release/symus-full.apk build/release/symus-lite.apk \
    --title "SYmuS $version" --notes "$notes"
