#!/usr/bin/env bash
# Microchip's ipecmd (MPLAB IPE) for PIC32 upload: found in an MPLAB X folder tree, put in place of a plain
# "ipecmd" in the upload command, the programmer presets with {chip}, and the installer check. Microchip's
# installer itself is not run (it needs root and installs system-wide).
#
#   1. find() takes the newest version of a stand-in /opt/microchip/mplabx tree
#   2. "ipecmd -TPPKOB ..." becomes that ipecmd; other commands stay as they are
#   3. every preset has {chip} and {hex}; the ESK II one uses PKOB and releases the chip from reset
#   4. only MPLAB X v6.20 installers for this computer are accepted, with IPE-only options
#   5. if MPLABX_INSTALLER is given (e.g. /home/samba/MPLABX-v6.20-linux-installer.tar), it passes the SHA-256 check
#
# Usage: scripts/check-ipe.sh [jar]
set -uo pipefail
cd "$(dirname "$0")/.."
JAR="${1:-$(ls target/evia-paradigma-*.jar | grep -v original | sort -V | tail -n 1)}"
OUT="$PWD/build/ipe-check"
rm -rf "$OUT" && mkdir -p "$OUT/java/evia/paradigma"
for v in v6.15 v6.20; do
  mkdir -p "$OUT/mplabx/$v/mplab_platform/mplab_ipe" && printf '#!/bin/sh\necho ipecmd %s\n' "$v" > "$OUT/mplabx/$v/mplab_platform/mplab_ipe/ipecmd.sh"
done
cat > "$OUT/java/evia/paradigma/IpeCheck.java" <<'EOF'
package evia.paradigma;
import java.nio.file.Path;
public class IpeCheck {
    static int ok, failed;
    static void check(boolean c, String what) { System.out.println((c ? "ok    " : "FAIL  ") + what); if (c) ok++; else failed++; }
    public static void main(String[] a) throws Exception {
        String root = System.getProperty("evia.mplabx.root");
        Path f = IpeInstaller.find();
        check(f != null && f.toString().equals(root + "/v6.20/mplab_platform/mplab_ipe/ipecmd.sh"), "find() takes the newest ipecmd: " + f);
        String esk = IpeInstaller.PRESETS[0][1];
        check(IpeInstaller.resolve(esk).equals(f + esk.substring("ipecmd".length())), "a plain ipecmd command runs the found ipecmd");
        check(IpeInstaller.resolve("pic32prog {hex}").equals("pic32prog {hex}"), "other commands stay as they are");
        boolean all = true;
        for (String[] p : IpeInstaller.PRESETS) all &= p[1].startsWith("ipecmd -TP") && p[1].contains("-P{chip}") && p[1].contains("-F{hex}") && p[1].contains(" -M");
        check(all, "every preset programs ({chip}, {hex}, -M)");
        check(esk.contains("-TPPKOB") && esk.endsWith("-OL") && IpeInstaller.PRESETS[0][0].contains("Ethernet Starter Kit II"),
                "the Ethernet Starter Kit II preset uses the on-board programmer and releases reset");
        check(esk.replace("{chip}", Board.PIC32MX795F512L.pic).contains("-P32MX795F512L"), "{chip} becomes 32MX795F512L for the ESK II's chip");
        check(IpeInstaller.isInstaller("MPLABX-v6.20-linux-installer.tar") && !IpeInstaller.isInstaller("MPLABX-v6.35-linux-installer.tar")
                && !IpeInstaller.isInstaller("xc32-v4.35-full-install-linux-x64-installer.tar"), "only the MPLAB X v6.20 installer is accepted");
        String opts = String.join(" ", IpeInstaller.installerOptions(false));
        check(opts.contains("--ide 0") && opts.contains("--ipe 1") && opts.contains("--32bitmcu 1") && opts.contains("--8bitmcu 0")
                && opts.contains("--collectInfo 0") && opts.contains("--mode unattended"), "installer options: IPE only, 32-bit devices, nothing collected");
        String inst = System.getenv("MPLABX_INSTALLER");
        if (inst != null && !inst.isEmpty()) {
            try { Xc32Installer.check(Path.of(inst), IpeInstaller.LINUX); check(true, "the installer " + inst + " is Microchip's v6.20 file (SHA-256)"); }
            catch (Exception e) { check(false, "installer check: " + e.getMessage()); }
            Path bad = Path.of(System.getProperty("java.io.tmpdir"), "MPLABX-v6.20-linux-installer.tar");
            java.nio.file.Files.writeString(bad, "not it");
            try { Xc32Installer.check(bad, IpeInstaller.LINUX); check(false, "a wrong file passed the check"); }
            catch (java.io.IOException e) { check(true, "a wrong file is refused"); }
            java.nio.file.Files.deleteIfExists(bad);
        }
        System.out.println();
        System.out.println(ok + " passed, " + failed + " failed");
        System.exit(failed);
    }
}
EOF
javac -cp "$JAR" -d "$OUT/java" "$OUT/java/evia/paradigma/IpeCheck.java" || exit 1
java -Devia.mplabx.root="$OUT/mplabx" -Djava.io.tmpdir="$OUT" -cp "$JAR:$OUT/java" evia.paradigma.IpeCheck
