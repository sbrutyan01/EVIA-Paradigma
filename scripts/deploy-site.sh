#!/usr/bin/env bash
# Builds the website and installs it where nginx serves it from. Run on the machine that serves it.
#
#   scripts/deploy-site.sh                  # build and install into /var/www/evia
#   scripts/deploy-site.sh --downloads      # also fetch this release's installers into downloads/
#   WEB_ROOT=/srv/evia scripts/deploy-site.sh
#
# The site is built for the newest published release, the same as on GitHub Pages, so the download
# links match files that exist. With --downloads the installers are fetched from that release into
# <root>/downloads/ and the links are pointed there instead of at GitHub, which is what makes this
# machine the place the downloads come from.
#
# No sudo: the web root has to be writable by whoever runs this, which
#   sudo chown -R "$USER":"$USER" /var/www/evia
# does once. Nothing here needs root, and a deploy script that wants root is a deploy script that
# can break a machine by accident.
set -uo pipefail
cd "$(dirname "$0")/.."

ROOT="${WEB_ROOT:-/var/www/evia}"
WANT_DOWNLOADS=0
REPO="${GITHUB_REPO:-sbrutyan01/EVIA-Paradigma}"

for a in "$@"; do
    case "$a" in
        --downloads) WANT_DOWNLOADS=1 ;;
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
        *) echo "unknown argument: $a" >&2; exit 2 ;;
    esac
done

die() { echo "deploy-site: $*" >&2; exit 1; }
say() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

command -v python3 > /dev/null || die "python3 is needed"
command -v curl > /dev/null || die "curl is needed"
[ -d "$ROOT" ] || die "$ROOT does not exist - see packaging/nginx/README.md"
[ -w "$ROOT" ] || die "$ROOT is not writable by $(id -un): sudo chown -R $(id -un) $ROOT"

# ---------------------------------------------------------------- which version the site is for
say "the newest published release"
TAG=$(curl -fsS "https://api.github.com/repos/$REPO/releases/latest" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin).get("tag_name",""))' 2>/dev/null)
[ -n "$TAG" ] || die "could not ask GitHub which release is newest"
VER="${TAG#v}"
echo "   $TAG"

# ---------------------------------------------------------------- build
say "building the site"
# The site embeds code samples produced by the generator, so it needs the jar. A fresh checkout
# has none, which is why this builds one when it is missing rather than failing on a glob.
JAR=$(ls -t target/evia-paradigma-*.jar 2> /dev/null | head -n 1)
if [ -z "$JAR" ]; then
    command -v mvn > /dev/null || die "no jar in target/ and no mvn to build one"
    echo "   no jar yet, building one (a minute or two)"
    mvn -B -q -DskipTests package > /tmp/evia-mvn.log 2>&1 \
        || { tail -20 /tmp/evia-mvn.log; die "the build failed; see /tmp/evia-mvn.log"; }
    JAR=$(ls -t target/evia-paradigma-*.jar 2> /dev/null | head -n 1)
    [ -n "$JAR" ] || die "the build produced no jar"
fi
echo "   jar: $JAR"
if [ "$WANT_DOWNLOADS" = 1 ]; then
    export DOWNLOAD_BASE="https://evia.am/downloads"
    echo "   download links -> $DOWNLOAD_BASE"
fi
SITE_VERSION="$TAG" python3 scripts/build-site.py "$JAR" || die "the site did not build"
[ -f build/site/index.html ] || die "build/site/index.html is missing"

# ---------------------------------------------------------------- install
say "installing into $ROOT"
# The downloads, packages and native folders are kept: the installers, the package server (scripts/build-packages.sh)
# and the update index of EVIA Paradigma Native (its native-sync service on dell)
# are large, and are not part of the site.
if command -v rsync > /dev/null; then
    rsync -a --delete --exclude downloads/ --exclude packages/ --exclude native/ build/site/ "$ROOT/" || die "rsync failed"
else
    find "$ROOT" -mindepth 1 -maxdepth 1 ! -name downloads ! -name packages ! -name native -exec rm -rf {} + 2> /dev/null
    cp -a build/site/. "$ROOT/" || die "copy failed"
fi
echo "   $(find "$ROOT" -type f ! -path "$ROOT/downloads/*" ! -path "$ROOT/packages/*" ! -path "$ROOT/native/*" | wc -l) files"

# ---------------------------------------------------------------- the installers, when asked for
if [ "$WANT_DOWNLOADS" = 1 ]; then
    say "the installers of $TAG"
    mkdir -p "$ROOT/downloads"
    curl -fsS "https://api.github.com/repos/$REPO/releases/tags/$TAG" > /tmp/evia-rel.json \
        || die "could not read the release"
    python3 - /tmp/evia-rel.json <<'PY' > /tmp/evia-assets.tsv
import json, sys
for a in json.load(open(sys.argv[1], encoding="utf-8")).get("assets", []):
    print(a["name"], a["browser_download_url"], a["size"], sep="\t")
PY
    while IFS=$'\t' read -r name url size; do
        f="$ROOT/downloads/$name"
        if [ -f "$f" ] && [ "$(wc -c < "$f")" = "$size" ]; then
            echo "   = $name (already there)"
            continue
        fi
        if curl -fsSL -o "$f.part" "$url" && [ "$(wc -c < "$f.part")" = "$size" ]; then
            mv "$f.part" "$f"; echo "   + $name ($size bytes)"
        else
            rm -f "$f.part"; echo "   ! $name did not download, or came out the wrong size"
        fi
    done < /tmp/evia-assets.tsv
    rm -f /tmp/evia-rel.json /tmp/evia-assets.tsv
fi

say "done"
echo "site    : https://evia.am/   (once nginx and the certificate are in place)"
echo "version : $VER"
[ "$WANT_DOWNLOADS" = 1 ] && echo "downloads served from this machine: $ROOT/downloads"
echo
echo "If the page does not change in a browser, it is cached: reload with Ctrl-Shift-R."
