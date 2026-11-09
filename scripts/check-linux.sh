#!/usr/bin/env bash
# Linux images (Linux menu): the workspaces that --linux writes, and their build.sh, without downloading anything.
# make, bitbake-layers and Yocto's oe-init-build-env are stand-ins that only record what they were asked, so this
# checks EVIA's part (the script, the choices, the root password hash), not Buildroot, Yocto or OpenWrt themselves.
#
#   1. --linux writes evia-linux.json, build.sh and secrets/ for 4 build systems x 2 boards; bash -n passes
#   2. Buildroot configure: the board's defconfig, then host name, SSH, Python, packages and the hashed password
#   3. Yocto configure: the layer, MACHINE, the extrausers line with the hash escaped, and again without doubling it
#   4. OpenWrt configure: the first-boot script with host name and hash; the source build's .config seed
#   5. the SD-card list keeps SD cards and USB drives and leaves out the disks of this computer
#
# Usage: scripts/check-linux.sh [jar]
set -uo pipefail
cd "$(dirname "$0")/.."
JAR="${1:-$(ls target/evia-paradigma-*.jar | grep -v original | sort -V | tail -n 1)}"
OUT="$PWD/build/linux-check"
rm -rf "$OUT" && mkdir -p "$OUT/bin"
ok=0; failed=0
pass() { echo "ok    $*"; ok=$((ok + 1)); }
fail() { echo "FAIL  $*"; failed=$((failed + 1)); }
has() { grep -qF -- "$2" "$1" && pass "$3" || { fail "$3: no '$2' in $1"; }; }

printf 'Secret pw1\n' > "$OUT/pw"
# stand-ins: make writes the defconfig it is given into O=/.config and records every call
cat > "$OUT/bin/make" <<'EOF'
#!/usr/bin/env bash
echo "make $*" >> "$STUB_LOG"
for a in "$@"; do case "$a" in O=*) o=${a#O=} ;; esac; done
for a in "$@"; do case "$a" in *_defconfig) mkdir -p "$o"; echo "# from $a" > "$o/.config" ;; esac; done
exit 0
EOF
cat > "$OUT/bin/bitbake-layers" <<'EOF'
#!/usr/bin/env bash
echo "bitbake-layers $*" >> "$STUB_LOG"
[ "$1" = add-layer ] && echo "BBLAYERS += \"$2\"" >> conf/bblayers.conf
exit 0
EOF
chmod +x "$OUT/bin/"*
export PATH="$OUT/bin:$PATH" STUB_LOG="$OUT/stub.log"

# 1
for s in buildroot yocto openwrt openwrt-source; do
  for b in bbb rpi5; do
    ws="$OUT/$s-$b"
    if java -jar "$JAR" --linux "$ws" --system "$s" --board "$b" --python --packages "htop nano" \
        --password-file "$OUT/pw" > "$ws.log" 2>&1 && bash -n "$ws/build.sh" && [ -f "$ws/evia-linux.json" ]; then
      pass "$s $b: workspace written, build.sh parses"
    else
      fail "$s $b: see $ws.log"
    fi
  done
done
[ "$(stat -c %a "$OUT/yocto-rpi5/secrets")" = 700 ] && [ "$(stat -c %a "$OUT/yocto-rpi5/secrets/root-password")" = 600 ] \
  && pass "secrets/ is owner-only" || fail "secrets/ permissions"
grep -q 'Secret pw1' "$OUT"/*/build.sh "$OUT"/*/evia-linux.json && fail "the password is in a script" || pass "the password is only in secrets/"
java -jar "$JAR" --linux "$OUT/bad path" > /dev/null 2>&1 && fail "a path with a space was accepted" || pass "a path with a space is refused"
java -jar "$JAR" --linux "$OUT/x" --hostname 'a;b' > /dev/null 2>&1 && fail "a bad host name was accepted" || pass "a bad host name is refused"

# 2
for b in bbb rpi5; do
  ws="$OUT/buildroot-$b"; mkdir -p "$ws/buildroot/.git"
  def=$([ $b = bbb ] && echo beaglebone_defconfig || echo raspberrypi5_defconfig)
  if (cd "$ws" && ./build.sh configure > "$ws/configure.out" 2>&1); then pass "buildroot $b: configure runs"; else fail "buildroot $b: configure (see $ws/configure.out)"; fi
  has "$ws/output/.config" "# from $def" "buildroot $b: $def"
  has "$ws/output/.config" "BR2_TARGET_GENERIC_HOSTNAME=\"evia-$b\"" "buildroot $b: host name"
  has "$ws/output/.config" 'BR2_PACKAGE_DROPBEAR=y' "buildroot $b: SSH server"
  has "$ws/output/.config" 'BR2_PACKAGE_PYTHON3=y' "buildroot $b: Python"
  has "$ws/output/.config" 'BR2_PACKAGE_HTOP=y' "buildroot $b: extra package"
  has "$ws/output/.config" 'BR2_TARGET_GENERIC_ROOT_PASSWD="$$6$$' "buildroot $b: password hash with \$\$"
done
has "$STUB_LOG" "olddefconfig" "buildroot: olddefconfig after the fragment"

# 3
ws="$OUT/yocto-rpi5"; mkdir -p "$ws/poky" "$ws/build/conf"
cat > "$ws/poky/oe-init-build-env" <<'EOF'
mkdir -p "$1/conf"; touch "$1/conf/local.conf" "$1/conf/bblayers.conf"; cd "$1"
EOF
for i in 1 2; do (cd "$ws" && ./build.sh configure > "$ws/configure.out" 2>&1) || fail "yocto: configure run $i (see $ws/configure.out)"; done
lc="$ws/build/conf/local.conf"
has "$lc" 'MACHINE = "raspberrypi5"' "yocto: MACHINE"
has "$lc" 'hostname:pn-base-files = "evia-rpi5"' "yocto: host name"
has "$lc" 'IMAGE_INSTALL:append = " python3 htop nano"' "yocto: packages"
has "$lc" "usermod -p '\\\$6\\\$" "yocto: password hash with \\\$"
grep -q 'empty-root-password' "$lc" && fail "yocto: passwordless although a password was given" || pass "yocto: no passwordless root"
has "$ws/build/conf/bblayers.conf" "meta-raspberrypi" "yocto: meta-raspberrypi added"
[ "$(grep -c '^MACHINE' "$lc")" = 1 ] && pass "yocto: configure twice writes the block once" || fail "yocto: the block is doubled"
[ "$(grep -c 'meta-raspberrypi' "$ws/build/conf/bblayers.conf")" = 1 ] && pass "yocto: the layer is added once" || fail "yocto: layer added twice"
grep -q 'beaglebone-yocto' "$OUT/yocto-bbb/build.sh" && ! grep -q 'meta-raspberrypi' "$OUT/yocto-bbb/build.sh" \
  && pass "yocto bbb: beaglebone-yocto, no extra layer" || fail "yocto bbb machine"

# 4
for b in bbb rpi5; do
  ws="$OUT/openwrt-$b"
  (cd "$ws" && ./build.sh configure > "$ws/configure.out" 2>&1) || fail "openwrt $b: configure"
  f="$ws/files/etc/uci-defaults/90-evia"
  has "$f" "hostname='evia-$b'" "openwrt $b: host name on first boot"
  has "$f" 'root:$6$' "openwrt $b: password hash on first boot"
done
has "$OUT/openwrt-rpi5/build.sh" 'PROFILE=rpi-5' "openwrt rpi5: profile"
has "$OUT/openwrt-rpi5/build.sh" 'openwrt-imagebuilder-25.12.6-bcm27xx-bcm2712.Linux-x86_64.tar.zst' "openwrt rpi5: image builder"
has "$OUT/openwrt-bbb/build.sh" 'PROFILE=ti_am335x-bone-black' "openwrt bbb: profile"
ws="$OUT/openwrt-source-bbb"; mkdir -p "$ws/openwrt/.git"
(cd "$ws" && ./build.sh configure > "$ws/configure.out" 2>&1) || fail "openwrt-source bbb: configure"
has "$ws/openwrt/.config" 'CONFIG_TARGET_omap_generic_DEVICE_ti_am335x-bone-black=y' "openwrt-source bbb: device"
has "$ws/openwrt/.config" 'CONFIG_PACKAGE_luci=y' "openwrt-source bbb: LuCI"

# 6: the sources come from the EVIA packages when they are installed (stand-in packages), and the pins agree
PK="$OUT/packages"
mkdir -p "$PK/buildroot" "$PK/yocto/poky" "$PK/yocto/meta-raspberrypi/conf" "$PK/openwrt/source" "$PK/openwrt/imagebuilder"
echo "# stand-in" > "$PK/buildroot/Makefile"
echo "# stand-in" > "$PK/yocto/poky/oe-init-build-env"
echo "# stand-in" > "$PK/openwrt/source/Makefile"
ibf=openwrt-imagebuilder-25.12.6-bcm27xx-bcm2712.Linux-x86_64.tar.zst
if command -v zstd > /dev/null; then
  mkdir -p "$OUT/ib/openwrt-imagebuilder-x" && echo "# stand-in" > "$OUT/ib/openwrt-imagebuilder-x/Makefile"
  tar --zstd -cf "$PK/openwrt/imagebuilder/$ibf" -C "$OUT/ib" openwrt-imagebuilder-x
  (cd "$PK/openwrt/imagebuilder" && sha256sum "$ibf" | sed 's/  / */' > sha256sums)
fi
export EVIA_PACKAGES="$PK"
ws="$OUT/buildroot-rpi5"; rm -rf "$ws/buildroot"
(cd "$ws" && ./build.sh fetch > fetch.out 2>&1) && [ -f "$ws/buildroot/Makefile" ] && grep -q 'from the EVIA package' "$ws/fetch.out" \
  && pass "buildroot: the sources come from the installed package" || fail "buildroot: fetch from the package (see $ws/fetch.out)"
ws="$OUT/yocto-rpi5"; rm -rf "$ws/poky"
(cd "$ws" && ./build.sh fetch > fetch.out 2>&1) && [ -f "$ws/poky/oe-init-build-env" ] && [ -d "$ws/meta-raspberrypi/conf" ] \
  && pass "yocto: poky and meta-raspberrypi come from the installed package" || fail "yocto: fetch from the package (see $ws/fetch.out)"
if command -v zstd > /dev/null; then
  ws="$OUT/openwrt-rpi5"
  (cd "$ws" && ./build.sh fetch > fetch.out 2>&1) && [ -f "$ws/imagebuilder/Makefile" ] && grep -q 'from the EVIA package' "$ws/fetch.out" \
    && pass "openwrt: the Image Builder comes from the installed package, checked" || fail "openwrt: fetch from the package (see $ws/fetch.out)"
  echo "broken" >> "$PK/openwrt/imagebuilder/$ibf"; rm -rf "$ws/imagebuilder"
  (cd "$ws" && ./build.sh fetch > fetch.out 2>&1) && fail "openwrt: a damaged Image Builder was used" || pass "openwrt: a damaged Image Builder is refused"
else
  echo "skip  openwrt Image Builder from the package (no zstd)"
fi
unset EVIA_PACKAGES
for v in BR_SHA POKY_SHA RPI_SHA OWRT_SHA RPI_COMMIT; do
  val=$(sed -n "s/^$v=//p" packaging/fetch-toolchains.sh)
  grep -rqF "$val" "$OUT"/*/build.sh && pass "the pin $v agrees with fetch-toolchains.sh" || fail "the pin $v ($val) is not in any build.sh"
done

# 5
mkdir -p "$OUT/java/evia/paradigma"
cat > "$OUT/java/evia/paradigma/DiskCheck.java" <<'EOF'
package evia.paradigma;
public class DiskCheck {
    public static void main(String[] a) throws Exception {
        String j = """
            {"blockdevices": [
              {"name":"nvme0n1","path":"/dev/nvme0n1","size":1000204886016,"model":"Samsung SSD","rm":false,"hotplug":false,"tran":"nvme","type":"disk","mountpoint":null,"ro":false,
               "children":[{"name":"nvme0n1p2","path":"/dev/nvme0n1p2","type":"part","mountpoint":"/","rm":false}]},
              {"name":"sda","path":"/dev/sda","size":31914983424,"model":"SD Reader","rm":true,"hotplug":true,"tran":"usb","type":"disk","mountpoint":null,"ro":false,
               "children":[{"name":"sda1","path":"/dev/sda1","type":"part","mountpoint":"/media/u/boot","rm":true}]},
              {"name":"mmcblk0","path":"/dev/mmcblk0","size":"15931539456","model":null,"rm":"0","hotplug":"0","tran":null,"type":"disk","mountpoint":null,"ro":"0"},
              {"name":"sdb","path":"/dev/sdb","size":64023257088,"model":"USB stick","rm":true,"hotplug":true,"tran":"usb","type":"disk","mountpoint":null,"ro":false,
               "children":[{"name":"sdb1","type":"part","mountpoint":"/home","rm":true}]},
              {"name":"loop0","path":"/dev/loop0","size":4096,"rm":false,"tran":null,"type":"loop","mountpoint":"/snap/x"}
            ]}""";
        StringBuilder sb = new StringBuilder();
        for (LinuxImages.Disk d : LinuxImages.disks(j)) sb.append(d.device()).append(' ');
        System.out.println(sb.toString().trim());
    }
}
EOF
javac -cp "$JAR" -d "$OUT/java" "$OUT/java/evia/paradigma/DiskCheck.java" 2>&1
got=$(java -cp "$JAR:$OUT/java" evia.paradigma.DiskCheck 2>&1)
[ "$got" = "/dev/sda /dev/mmcblk0" ] && pass "SD cards: $got (not the system disk, not a drive with /home)" || fail "SD cards: got '$got'"

echo
echo "$ok passed, $failed failed"
exit "$failed"
