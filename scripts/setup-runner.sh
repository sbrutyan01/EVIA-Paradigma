#!/usr/bin/env bash
# Sets up this computer to run the EVIA Paradigma checks for GitHub: build tools, a Java 25 that
# mvn can use, and a self-hosted runner installed as a service that starts with the machine.
#
#   scripts/setup-runner.sh --check                 # only say what is here and what is missing
#   scripts/setup-runner.sh --token <TOKEN>         # install everything, register the runner
#   scripts/setup-runner.sh --token <TOKEN> --name dell --labels self-hosted,linux,dell
#
# The token comes from the repository page: Settings > Actions > Runners > New self-hosted runner.
# It is valid for about an hour and is used once, here, to register this machine.
#
# Nothing is forwarded on your router: the runner polls GitHub outbound. The runner is installed in
# ~/actions-runner and runs as this user, never as root.
set -euo pipefail

REPO="${REPO:-https://github.com/sbrutyan01/EVIA-Paradigma}"
NAME="${NAME:-$(hostname -s)}"
LABELS="${LABELS:-self-hosted,linux,dell}"
DIR="${DIR:-$HOME/actions-runner}"
TOKEN=""
CHECK=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        --check) CHECK=1 ;;
        --token) TOKEN="${2:?--token needs the registration token}"; shift ;;
        --name) NAME="${2:?}"; shift ;;
        --labels) LABELS="${2:?}"; shift ;;
        --repo) REPO="${2:?}"; shift ;;
        --dir) DIR="${2:?}"; shift ;;
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
        *) echo "unknown option: $1"; exit 2 ;;
    esac
    shift
done

say()  { printf '\n\033[1m== %s\033[0m\n' "$1"; }
have() { command -v "$1" > /dev/null 2>&1; }

# ---------------------------------------------------------------- what is already here

say "this machine"
printf '%-14s %s\n' host "$(hostname) ($(uname -m), $(nproc) cores)"
[ -r /etc/os-release ] && printf '%-14s %s\n' system "$(. /etc/os-release && echo "$PRETTY_NAME")"
# the major number of the java on the PATH, or 0 when there is none
java_major() { java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1 | grep . || echo 0; }
for t in git mvn java clang++ g++ python3 xvfb-run xc32-gcc avr-gcc arduino-cli yosys iverilog; do
    printf '%-14s %s\n' "$t" "$(command -v "$t" || echo '-')"
done
have java && printf '%-14s %s\n' 'java version' "$(java_major)"

missing=()
for t in git mvn clang++ g++ python3 xvfb-run; do have "$t" || missing+=("$t"); done
if [ "${#missing[@]}" -gt 0 ]; then
    echo "to install: ${missing[*]}"
fi

if [ "$CHECK" = 1 ]; then
    say "optional, each unlocks more checks"
    echo "  OSS CAD Suite -> /opt/oss-cad-suite    the FPGA checks (yosys, nextpnr, iverilog, ghdl)"
    echo "  XC32          -> /opt/microchip/...    the PIC32 checks (xc32-gcc)"
    echo "  avr-gcc + arduino-cli                  the board checks (simavr, 12 boards)"
    exit 0
fi

[ -n "$TOKEN" ] || { echo "no --token: get one from Settings > Actions > Runners > New self-hosted runner"; exit 2; }

# ---------------------------------------------------------------- build tools and Java 25

say "build tools"
sudo apt-get update -qq
sudo apt-get install -y -qq git maven build-essential clang python3 xvfb curl tar jq

if [ "$(java_major)" -lt 25 ]; then
    say "Java 25"
    if apt-cache show openjdk-25-jdk > /dev/null 2>&1; then
        sudo apt-get install -y -qq openjdk-25-jdk
    else
        echo "Debian has no openjdk-25-jdk here: taking Temurin 25 from Adoptium"
        sudo install -d -m 0755 /etc/apt/keyrings
        curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
            | sudo tee /etc/apt/keyrings/adoptium.asc > /dev/null
        echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" \
            | sudo tee /etc/apt/sources.list.d/adoptium.list > /dev/null
        sudo apt-get update -qq
        sudo apt-get install -y -qq temurin-25-jdk
    fi
fi
java -version 2>&1 | head -1
[ "$(java_major)" -ge 25 ] || { echo "java is still $(java_major), not 25: fix that first (update-alternatives --config java)"; exit 1; }

# ---------------------------------------------------------------- the runner itself

say "the GitHub runner"
mkdir -p "$DIR"
cd "$DIR"
if [ ! -x ./config.sh ]; then
    ver=$(curl -fsSL https://api.github.com/repos/actions/runner/releases/latest | jq -r .tag_name)
    ver=${ver#v}
    tgz="actions-runner-linux-x64-$ver.tar.gz"
    echo "runner $ver"
    curl -fsSL --retry 5 -o "$tgz" "https://github.com/actions/runner/releases/download/v$ver/$tgz"
    tar xzf "$tgz"
    rm -f "$tgz"
fi
if [ -f .runner ]; then
    echo "already registered as $(jq -r .agentName .runner 2> /dev/null || echo '?'): leaving it alone"
else
    ./config.sh --url "$REPO" --token "$TOKEN" --name "$NAME" --labels "$LABELS" --unattended --replace
fi

say "the service"
if [ -f ./svc.sh ] && ! sudo ./svc.sh status > /dev/null 2>&1; then
    sudo ./svc.sh install "$USER"
    sudo ./svc.sh start
fi
sudo ./svc.sh status || true

say "done"
echo "The runner should now show as idle under Settings > Actions > Runners."
echo "Start a run from Actions > local-ci > Run workflow, or on this machine: scripts/local-ci.sh"
