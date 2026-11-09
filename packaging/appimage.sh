#!/usr/bin/env bash
# Builds a generic Linux AppImage from the application image jpackage has already produced.
#
#   packaging/appimage.sh build/jpackage/image/"EVIA Paradigma" 0.7.1
#       -> packages/EVIA-Paradigma-0.7.1-x86_64.AppImage
#
# Why an AppImage as well as the .deb: the .deb installs into /opt and wants apt, so it fits
# Debian and Ubuntu and nothing else. An AppImage is one executable file that runs on any
# reasonably current distribution with no installation, no root and no package manager - which is
# what people on Fedora, Arch, openSUSE or a live system actually need. The runtime jpackage
# bundled is inside it, so Java is not required either.
#
# appimagetool is fetched from its own GitHub release and run with --appimage-extract-and-run,
# because CI machines have no FUSE and would otherwise fail to mount it.
set -euo pipefail

IMAGE="${1:?usage: appimage.sh <jpackage app-image dir> <version>}"
VERSION="${2:?usage: appimage.sh <jpackage app-image dir> <version>}"
[ -d "$IMAGE" ] || { echo "appimage: no application image at $IMAGE" >&2; exit 1; }

OUT=packages
WORK=build/appimage
APPDIR="$WORK/EVIA-Paradigma.AppDir"
ARCH="${ARCH:-$(uname -m)}"
TOOL_URL="https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-${ARCH}.AppImage"

rm -rf "$WORK"
mkdir -p "$APPDIR/usr" "$OUT"

# ---- the application itself, exactly as jpackage built it
cp -a "$IMAGE/." "$APPDIR/usr/"
LAUNCHER=$(ls "$APPDIR/usr/bin" | head -n 1)
[ -n "$LAUNCHER" ] || { echo "appimage: no launcher in $IMAGE/bin" >&2; exit 1; }
echo "appimage: launcher is $LAUNCHER"

# ---- AppRun: what the AppImage executes. $APPDIR is set by the runtime at start.
cat > "$APPDIR/AppRun" <<'APPRUN'
#!/usr/bin/env bash
# Entry point of the AppImage. The runtime mounts the bundle and sets APPDIR.
HERE="${APPDIR:-$(dirname "$(readlink -f "$0")")}"
# The bundled runtime and the application's own files are all under usr/.
exec "$HERE/usr/bin/EVIA Paradigma" "$@"
APPRUN
sed -i "s|usr/bin/EVIA Paradigma|usr/bin/$LAUNCHER|" "$APPDIR/AppRun"
chmod +x "$APPDIR/AppRun"

# ---- the desktop entry, which an AppImage must carry at the top of the AppDir
cat > "$APPDIR/evia-paradigma.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=EVIA Paradigma
GenericName=Visual block programming
Comment=Visual block programming with classes, and code generation for C, C++, Python and boards
Exec=AppRun %f
Icon=evia-paradigma
Categories=Development;IDE;
Terminal=false
MimeType=application/x-evia;application/x-evia-project;
StartupWMClass=evia-paradigma
X-AppImage-Version=$VERSION
DESKTOP

# ---- the icon, at the name the desktop entry asks for
if [ -f packaging/icons/evia.png ]; then
    cp packaging/icons/evia.png "$APPDIR/evia-paradigma.png"
    install -Dm644 packaging/icons/evia.png \
        "$APPDIR/usr/share/icons/hicolor/256x256/apps/evia-paradigma.png"
else
    echo "appimage: packaging/icons/evia.png is missing" >&2; exit 1
fi
install -Dm644 "$APPDIR/evia-paradigma.desktop" \
    "$APPDIR/usr/share/applications/evia-paradigma.desktop"

# ---- it has to actually start before it is worth packaging
echo "appimage: smoke test through AppRun"
APPDIR="$APPDIR" "$APPDIR/AppRun" --run examples/example.evia > "$WORK/smoke.out"
grep -q "average of evens so far: 4" "$WORK/smoke.out" \
    || { echo "appimage: the application did not run from the AppDir"; cat "$WORK/smoke.out"; exit 1; }

# ---- appimagetool
TOOL="$WORK/appimagetool"
if ! curl -fsSL -o "$TOOL" "$TOOL_URL"; then
    echo "appimage: could not download appimagetool from $TOOL_URL" >&2
    exit 1
fi
chmod +x "$TOOL"

TARGET="$OUT/EVIA-Paradigma-${VERSION}-${ARCH}.AppImage"
# No signing key here, so --no-appstream keeps it from demanding metadata we do not ship.
ARCH="$ARCH" "$TOOL" --appimage-extract-and-run --no-appstream "$APPDIR" "$TARGET"
chmod +x "$TARGET"

echo "appimage: $TARGET  ($(wc -c < "$TARGET") bytes)"

# ---- and the finished file has to run too, not just the directory it was made from
echo "appimage: smoke test of the finished AppImage"
"$TARGET" --appimage-extract-and-run --run examples/example.evia > "$WORK/smoke2.out" 2>&1 \
    || "$TARGET" --run examples/example.evia > "$WORK/smoke2.out" 2>&1
grep -q "average of evens so far: 4" "$WORK/smoke2.out" \
    || { echo "appimage: the finished AppImage did not run"; tail -20 "$WORK/smoke2.out"; exit 1; }
echo "appimage: it runs"
