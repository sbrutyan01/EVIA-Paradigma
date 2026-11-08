#!/usr/bin/env bash
# Sets this machine up to serve the site at a domain over HTTPS, with GitLab kept private.
# Run it with sudo, on the machine itself. It is idempotent: running it again is safe.
#
#   sudo packaging/nginx/setup.sh --stage1          # everything that does not need the router
#   ... forward TCP 80 and TCP 443 to this machine ...
#   sudo packaging/nginx/setup.sh --stage2          # the certificate and the real config
#   packaging/nginx/setup.sh --check               # what is done and what is not (no sudo needed)
#
#   DOMAIN=evia.am   the domain (default evia.am)
#   LAN_IP           this machine's address on the network, for the GitLab block
#
# Why two stages: certbot proves the domain by answering on port 80 from the internet, so the
# router has to forward it first. Everything that does not depend on the router happens in stage 1,
# so the window where this machine is reachable is as short as possible.
#
# What it deliberately does NOT do: touch the router, open any firewall, or expose GitLab. Those
# are yours to decide, and the first two cannot be done from here anyway.
set -uo pipefail
cd "$(dirname "$0")/../.."
HERE="packaging/nginx"

DOMAIN="${DOMAIN:-evia.am}"
# Where a failing renewal is reported to. Overridden with EMAIL=...
EMAIL="${EMAIL:-$(git config user.email 2> /dev/null || true)}"
EMAIL="${EMAIL:-sbrutyan01@gmail.com}"
SELF="/etc/ssl/evia"
STAGE=""
DEPLOY=0
SELFSIGNED=0
for a in "$@"; do
    case "$a" in
        --stage1) STAGE=1 ;;
        --stage2) STAGE=2 ;;
        --check) STAGE=check ;;
        --deploy) DEPLOY=1 ;;
        --selfsigned) SELFSIGNED=1 ;;
        -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
        *) echo "unknown argument: $a" >&2; exit 2 ;;
    esac
done
[ -n "$STAGE" ] || { sed -n '2,18p' "$0"; exit 2; }

say()  { printf '\n\033[1m== %s\033[0m\n' "$1"; }
ok()   { printf '   \033[32mok\033[0m    %s\n' "$1"; }
warn() { printf '   \033[33mnote\033[0m  %s\n' "$1"; }
die()  { printf '\n\033[31msetup: %s\033[0m\n' "$1" >&2; exit 1; }

# The user who invoked sudo: the web root belongs to them, so deploys need no root.
OWNER="${SUDO_USER:-$(id -un)}"
LAN_IP="${LAN_IP:-$(ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p')}"

# ---------------------------------------------------------------- --check
if [ "$STAGE" = check ]; then
    say "what is in place"
    for c in nginx certbot gitlab-ctl; do
        printf '   %-12s %s\n' "$c" "$(command -v $c || echo 'not installed')"
    done
    for p in /var/www/evia /var/www/acme /etc/nginx/sites-enabled/evia.am \
             "/etc/letsencrypt/live/$DOMAIN/fullchain.pem"; do
        [ -e "$p" ] && ok "$p" || warn "missing: $p"
    done
    say "ports"
    ss -ltn 2>/dev/null | awk 'NR==1 || $4 ~ /:(80|443|8081)$/'
    say "$DOMAIN"
    ips=$(getent ahostsv4 "$DOMAIN" 2>/dev/null | awk '{print $1}' | sort -u | tr '\n' ' ')
    pub=$(curl -fsS -m 8 https://api.ipify.org 2>/dev/null)
    echo "   resolves to : ${ips:-nothing}"
    echo "   this network: ${pub:-unknown}"
    [ -n "$pub" ] && echo "$ips" | grep -qw "$pub" && ok "the domain points here" \
        || warn "the domain does not point here (yet) - stage 2 would fail"
    exit 0
fi

[ "$(id -u)" = 0 ] || die "run this with sudo"
[ -n "$LAN_IP" ] || die "could not work out this machine's address; set LAN_IP"

# ---------------------------------------------------------------- stage 1
if [ "$STAGE" = 1 ]; then
    say "GitLab off port 80"
    if command -v gitlab-ctl > /dev/null; then
        RB=/etc/gitlab/gitlab.rb
        [ -f "$RB" ] || die "$RB is missing, yet gitlab-ctl is installed"
        if grep -q "listen_port'\] = 8081" "$RB"; then
            ok "already set to 127.0.0.1:8081"
        else
            cp -a "$RB" "$RB.before-evia-$(date +%Y%m%d%H%M%S)"
            ok "kept a copy of gitlab.rb"
            # Comment out any existing settings we are about to replace, then append ours.
            sed -i -E "s/^[[:space:]]*(nginx\['listen_(addresses|port|https)'\])/# &/" "$RB"
            cat >> "$RB" <<EOF

# Added by packaging/nginx/setup.sh: GitLab sits behind the system nginx, which needs port 80.
nginx['listen_addresses'] = ['127.0.0.1']
nginx['listen_port'] = 8081
nginx['listen_https'] = false
gitlab_rails['trusted_proxies'] = ['127.0.0.1']
EOF
            ok "gitlab.rb updated"
            gitlab-ctl reconfigure > /tmp/evia-gitlab-reconfigure.log 2>&1 \
                || die "gitlab-ctl reconfigure failed; see /tmp/evia-gitlab-reconfigure.log"
            ok "gitlab-ctl reconfigure done"
        fi
        ss -ltn | grep -q '127.0.0.1:8081' && ok "GitLab is on 127.0.0.1:8081" \
            || warn "nothing is listening on 127.0.0.1:8081 - check gitlab-ctl status"
        ss -ltn | grep -qE '0\.0\.0\.0:80 ' && die "something still holds 0.0.0.0:80; nginx cannot start"
    else
        ok "no GitLab here, so port 80 is free"
    fi

    say "nginx and certbot"
    if ! command -v nginx > /dev/null || ! command -v certbot > /dev/null; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq \
            && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nginx certbot python3-certbot-nginx \
            || die "could not install nginx and certbot"
    fi
    ok "nginx $(nginx -v 2>&1 | sed 's/.*\///'), certbot $(certbot --version 2>&1 | awk '{print $2}')"

    say "the web root"
    mkdir -p /var/www/acme /var/www/evia
    chown -R "$OWNER":"$OWNER" /var/www/evia
    ok "/var/www/evia belongs to $OWNER, so deploys need no root"

    say "the first config, port 80 only"
    sed "s/192\.168\.10\.13/$LAN_IP/g; s/evia\.am/$DOMAIN/g" \
        "$HERE/evia.am.bootstrap.conf" > /etc/nginx/sites-available/evia.am
    ln -sf /etc/nginx/sites-available/evia.am /etc/nginx/sites-enabled/evia.am
    rm -f /etc/nginx/sites-enabled/default
    nginx -t 2> /tmp/evia-nginx-t.log || { cat /tmp/evia-nginx-t.log; die "nginx rejected the config"; }
    systemctl enable --now nginx > /dev/null 2>&1
    systemctl reload nginx || systemctl restart nginx || die "nginx would not reload"
    ok "nginx serves $DOMAIN on port 80, and GitLab for $LAN_IP only"

    # A certificate made here, so HTTPS works at once and nginx can load the full config before
    # Let's Encrypt has run. No browser trusts it - it warns every visitor - so it is a stand-in
    # until stage 2, and certbot overwrites these paths with a real certificate.
    if [ "$SELFSIGNED" = 1 ]; then
        say "a certificate of our own (openssl)"
        mkdir -p "$SELF"
        if [ -f "$SELF/fullchain.pem" ]; then
            ok "already there: $SELF/fullchain.pem"
        else
            openssl req -x509 -newkey rsa:2048 -nodes -days 365 -sha256 \
                -keyout "$SELF/privkey.pem" -out "$SELF/fullchain.pem" \
                -subj "/CN=$DOMAIN" \
                -addext "subjectAltName=DNS:$DOMAIN,DNS:www.$DOMAIN,IP:$LAN_IP" \
                2> /dev/null || die "openssl could not make a certificate"
            chmod 600 "$SELF/privkey.pem"
            ok "made one for $DOMAIN, www.$DOMAIN and $LAN_IP, valid a year"
        fi
        sed "s/192\.168\.10\.13/$LAN_IP/g; s/evia\.am/$DOMAIN/g; \
             s#/etc/letsencrypt/live/$DOMAIN#$SELF#g" \
            "$HERE/evia.am.conf" > /etc/nginx/sites-available/evia.am
        nginx -t 2> /tmp/evia-nginx-t.log || { cat /tmp/evia-nginx-t.log; die "nginx rejected the config"; }
        systemctl reload nginx || die "nginx would not reload"
        ok "HTTPS is up with our own certificate - browsers will warn until stage 2"
        warn "this certificate is NOT trusted by anyone: fine for testing and on this network,"
        warn "not for visitors. Stage 2 replaces it with a real one."
    fi

    say "stage 1 done"
    cat <<EOF
   Next, on the router: forward TCP 80 and TCP 443 to $LAN_IP. Nothing else - not 8081, not 22.

   Then, once "$0 --check" says the domain points here:
       sudo EMAIL=$EMAIL $0 --stage2 --deploy
EOF
    exit 0
fi

# ---------------------------------------------------------------- stage 2
if [ "$STAGE" = 2 ]; then
    say "before asking for a certificate"
    command -v certbot > /dev/null || die "certbot is not installed; run --stage1 first"
    [ -f /etc/nginx/sites-enabled/evia.am ] || die "the nginx config is not in place; run --stage1 first"
    ips=$(getent ahostsv4 "$DOMAIN" 2>/dev/null | awk '{print $1}' | sort -u)
    pub=$(curl -fsS -m 10 https://api.ipify.org 2>/dev/null)
    [ -n "$ips" ] || die "$DOMAIN does not resolve. Wait for DNS; certbot would fail and
    Let's Encrypt limits how often you may fail."
    [ -n "$pub" ] && echo "$ips" | grep -qw "$pub" \
        || die "$DOMAIN resolves to ${ips//$'\n'/ } but this connection is ${pub:-unknown}.
    Fix the record, or wait for it to propagate, before asking for a certificate."
    ok "$DOMAIN points at this connection"

    say "the certificate"
    if [ -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]; then
        ok "already have one; certbot renews it by itself"
    else
        # An address gets you the warning when a renewal has been failing, which is the one
        # notification worth having.
        if [ -n "$EMAIL" ]; then
            reg=(-m "$EMAIL")
            ok "renewal problems will be reported to $EMAIL"
        else
            reg=(--register-unsafely-without-email)
            warn "no address, so nothing will warn you if a renewal starts failing"
        fi
        certbot --nginx --non-interactive --agree-tos --no-eff-email "${reg[@]}" \
                -d "$DOMAIN" -d "www.$DOMAIN" \
            || die "certbot failed. Almost always port 80 is not reaching this machine from the
    internet: check the forwarding rule. Its log is in /var/log/letsencrypt/."
        ok "certificate obtained"
    fi
    systemctl list-timers 2>/dev/null | grep -q certbot && ok "renewal timer is active" \
        || warn "no certbot timer; check: systemctl enable --now certbot.timer"

    say "the real config"
    sed "s/192\.168\.10\.13/$LAN_IP/g; s/evia\.am/$DOMAIN/g" \
        "$HERE/evia.am.conf" > /etc/nginx/sites-available/evia.am
    nginx -t 2> /tmp/evia-nginx-t.log || { cat /tmp/evia-nginx-t.log; die "nginx rejected the config"; }
    systemctl reload nginx || die "nginx would not reload"
    ok "HTTPS, with an unknown Host getting nothing, and GitLab on this network only"

    if [ "$DEPLOY" = 1 ]; then
        say "the site"
        sudo -u "$OWNER" scripts/deploy-site.sh --downloads || warn "the deploy did not finish"
    fi

    say "stage 2 done"
    echo "   https://$DOMAIN/"
    echo "   Check it from outside your network too - a phone on mobile data is the easiest way."
    exit 0
fi
