#!/usr/bin/env bash
# Builds a native installer with jpackage (JDK 25), bundling a minimal Java runtime,
# so users don't need Java installed.
#
#   packaging/package.sh linux        -> packages/evia-paradigma_<ver>_amd64.deb
#   packaging/package.sh windows      -> packages/EVIA-Paradigma-<ver>-windows-x64.exe
#   packaging/package.sh macos-arm64  -> packages/EVIA-Paradigma-<ver>-macos-arm64.dmg
#   packaging/package.sh macos-x64    -> packages/EVIA-Paradigma-<ver>-macos-x64.dmg
#
# Expects the application jar in dist/input/ (from "mvn package"). Run from the repository root.
set -euo pipefail

KIND="${1:?usage: package.sh linux|windows|macos-arm64|macos-x64}"
VERSION=$(sed -n 's:^    <version>\(.*\)</version>:\1:p' pom.xml | head -n 1)
NAME="EVIA Paradigma"
JAR=$(ls dist/input/evia-paradigma-*.jar | head -n 1)
WORK=build/jpackage
OUT=packages
rm -rf "$WORK"
mkdir -p "$WORK" "$OUT"
echo "Packaging $NAME $VERSION for $KIND using $(jpackage --version)"

# ---- 1. application image: launcher + jar + minimal runtime (java.base, java.desktop, java.prefs)
image_opts=(
  --name "$NAME"
  --vendor "Sergey Brutyan (sbrutyan01)"
  --copyright "Copyright (c) 2026 Sergey Brutyan (sbrutyan01). MIT License."
  --description "Visual block programming with classes and C++20 generation"
  --input dist/input
  --main-jar "$(basename "$JAR")"
  --main-class evia.paradigma.App
  --add-modules java.base,java.desktop,java.prefs,java.logging
  --jlink-options "--strip-debug --no-man-pages --no-header-files --strip-native-commands"
  --dest "$WORK/image"
)

smoke_test() {   # run the bundled app in command-line mode on an example program
  echo "Smoke test: $*"
  "$@" --run examples/example.evia | tee "$WORK/smoke.out"
  grep -q "average of evens so far: 4" "$WORK/smoke.out"
}

case "$KIND" in
  linux)
    jpackage --type app-image "${image_opts[@]}" --app-version "$VERSION" --icon packaging/icons/evia.png
    smoke_test "$WORK/image/$NAME/bin/$NAME"
    jpackage --type deb --app-image "$WORK/image/$NAME" --name "$NAME" --app-version "$VERSION" \
      --vendor "Sergey Brutyan (sbrutyan01)" \
      --description "Visual block programming with classes and C++20 generation" \
      --license-file LICENSE \
      --file-associations packaging/evia-files.properties --file-associations packaging/evia-project.properties \
      --icon packaging/icons/evia.png \
      --linux-package-name evia-paradigma \
      --linux-deb-maintainer sbrutyan01@gmail.com \
      --linux-menu-group "Development;IDE" \
      --linux-app-category devel \
      --linux-shortcut \
      --resource-dir packaging/linux \
      --about-url https://github.com/sbrutyan01/EVIA-Paradigma \
      --dest "$WORK/installer"
    mv "$WORK"/installer/*.deb "$OUT/evia-paradigma_${VERSION}_amd64.deb"
    ;;

  windows)
    # second launcher "evia.exe" is a console program for the command line: evia --run x.evia
    jpackage --type app-image "${image_opts[@]}" --app-version "$VERSION" --icon packaging/icons/evia.ico \
      --add-launcher evia=packaging/evia-cli.properties
    smoke_test "$WORK/image/$NAME/evia.exe"
    jpackage --type exe --app-image "$WORK/image/$NAME" --name "$NAME" --app-version "$VERSION" \
      --vendor "Sergey Brutyan (sbrutyan01)" \
      --description "Visual block programming with classes and C++20 generation" \
      --license-file LICENSE \
      --file-associations packaging/evia-files.properties --file-associations packaging/evia-project.properties \
      --icon packaging/icons/evia.ico \
      --win-menu --win-menu-group "EVIA" \
      --win-shortcut --win-shortcut-prompt \
      --win-dir-chooser \
      --win-upgrade-uuid 6695b4ed-ebb6-48bf-80c4-46391ab56990 \
      --dest "$WORK/installer"
    mv "$WORK"/installer/*.exe "$OUT/EVIA-Paradigma-${VERSION}-windows-x64.exe"
    ;;

  macos-arm64|macos-x64)
    ARCH="${KIND#macos-}"
    # macOS bundle versions must start with 1 or higher; the visible version is set below
    MACVER="$VERSION"
    [[ "$VERSION" == 0.* ]] && MACVER="1.${VERSION#0.}"
    jpackage --type app-image "${image_opts[@]}" --app-version "$MACVER" --icon packaging/icons/evia.icns \
      --mac-package-identifier io.github.sbrutyan01.eviaparadigma \
      --mac-package-name "$NAME" \
      --file-associations packaging/evia-files.properties --file-associations packaging/evia-project.properties
    APP="$WORK/image/$NAME.app"
    /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" "$APP/Contents/Info.plist"
    codesign --force --deep --sign - "$APP"          # ad-hoc signature after editing Info.plist
    smoke_test "$APP/Contents/MacOS/$NAME"
    jpackage --type dmg --app-image "$APP" --name "$NAME" --app-version "$MACVER" \
      --mac-package-identifier io.github.sbrutyan01.eviaparadigma \
      --dest "$WORK/installer"
    mv "$WORK"/installer/*.dmg "$OUT/EVIA-Paradigma-${VERSION}-macos-${ARCH}.dmg"
    ;;

  *)
    echo "unknown package kind: $KIND" >&2
    exit 2
    ;;
esac

ls -l "$OUT"
