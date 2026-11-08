#!/usr/bin/env bash
# Sets this machine up to serve the site at a domain over HTTPS, with GitLab kept private.
# Run it with sudo, on the machine itself. It is idempotent: running it again is safe.
#
#   sudo packaging/nginx/setup.sh --all --allow=1883,8443      # the whole thing
#   packaging/nginx/setup.sh --check                           # the state of it, without sudo
#
# or one piece at a time:
#
#   sudo packaging/nginx/setup.sh --firewall --allow=1883,8443 # close what should not be open
#   sudo packaging/nginx/setup.sh --stage1 --selfsigned        # nginx, the web root, GitLab moved
#   sudo packaging/nginx/setup.sh --stage2                     # the trusted certificate
#
#   DOMAIN=evia.am      the domain (default evia.am)
#   EMAIL=...           where a failing renewal is reported (default: this repository's git address)
#   ALLOW_PORTS=...     same as --allow=
#   LAN_IP              this machine's own address, if it cannot be worked out
#
# Why --all can stop short: everything except the certificate is under this machine's control. A
# certificate needs the domain to point here first, which is DNS and nobody's to rush, so --all
# does the rest, leaves the site served over http and its own certificate, and says what to add.
#
# What it deliberately does NOT do: expose GitLab, which stays on localhost and the VPN; open any
# port beyond ssh, http, https and whatever --allow names; or touch DNS.
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
NO_FIREWALL=0
# Ports that stay open to the internet besides ssh, http and https. Services of your own that
# other machines reach across the internet go here - a broker a board publishes to, a camera you
# watch from elsewhere. Anything only you use is better left closed and reached over Tailscale.
ALLOW_PORTS="${ALLOW_PORTS:-}"
for a in "$@"; do
    case "$a" in
        --stage1) STAGE=1 ;;
        --stage2) STAGE=2 ;;
        --check) STAGE=check ;;
        --deploy) DEPLOY=1 ;;
        --selfsigned) SELFSIGNED=1 ;;
        --all) STAGE=all ;;
        --config) STAGE=config ;;
        --firewall) STAGE=firewall ;;
        --no-firewall) NO_FIREWALL=1 ;;
        --allow=*) ALLOW_PORTS="${a#--allow=}" ;;
        --allow) echo "write it as --allow=1883,8443" >&2; exit 2 ;;
        -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
        *) echo "unknown argument: $a" >&2; exit 2 ;;
    esac
done
[ -n "$STAGE" ] || { sed -n "2,22p" "$0"; exit 2; }

say()  { printf '\n\033[1m== %s\033[0m\n' "$1"; }
ok()   { printf '   \033[32mok\033[0m    %s\n' "$1"; }
warn() { printf '   \033[33mnote\033[0m  %s\n' "$1"; }
die()  { printf '\n\033[31msetup: %s\033[0m\n' "$1" >&2; exit 1; }

# The user who invoked sudo: the web root belongs to them, so deploys need no root.
OWNER="${SUDO_USER:-$(id -un)}"
LAN_IP="${LAN_IP:-$(ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p')}"

# Is this machine's own address private, or is it straight on a public one? It decides who may
# reach GitLab: on a public address the surrounding /24 is strangers, not "the local network".
private_address() {
    case "$1" in
        10.*|127.*|192.168.*) return 0 ;;
        172.1[6-9].*|172.2[0-9].*|172.3[01].*) return 0 ;;
        *) return 1 ;;
    esac
}

# The allow list that replaces #__ALLOW__ in the nginx configs.
allow_lines() {
    printf '    allow 127.0.0.1;\n'
    printf '    allow 100.64.0.0/10;   # Tailscale\n'
    if private_address "$LAN_IP"; then
        printf '    allow %s;\n' "$(echo "$LAN_IP" | sed 's/\.[0-9]*$/.0\/24/')"
    fi
}

# Writes an nginx config with the addresses and the allow list filled in.
install_conf() {   # source file, destination
    local allow sub
    allow=$(allow_lines)
    if private_address "$LAN_IP"; then
        # A private address is safe as a name for the GitLab block: only this network can send it.
        sub="s/192\.168\.10\.13/$LAN_IP/g"
    else
        # A public address must NOT name the GitLab block, or every request arriving by address
        # matches it and is refused by its deny rule - including the site over http.
        sub="s/ *192\.168\.10\.13//g"
    fi
    sed "$sub; s/evia\.am/$DOMAIN/g" "$1" \
        | awk -v a="$allow" '/#__ALLOW__/ { print a; next } { print }' > "$2"
}

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

# ---------------------------------------------------------------- the firewall
#
# This machine may sit directly on a public address, with no router translating anything - in which
# case every service listening on 0.0.0.0 is on the internet. A web server belongs there; SMB, a
# message broker and a GitLab sign-in page over plain http do not.
#
# What this allows in: ssh, http, https, and anything over Tailscale. Everything else is refused
# from the internet while staying available on this machine and over the VPN, so GitLab, MQTT and
# file sharing keep working the way they are used - just not for strangers.
firewall() {
    say "the firewall"
    if ! command -v ufw > /dev/null; then
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ufw || die "could not install ufw"
    fi
    # Order matters: the allow rules go in BEFORE the default deny is applied, so an ssh session
    # cannot be cut off half way through.
    ufw --force reset > /dev/null 2>&1
    ufw default deny incoming > /dev/null
    ufw default allow outgoing > /dev/null
    ufw allow 22/tcp comment 'ssh' > /dev/null
    ufw allow 80/tcp comment 'http' > /dev/null
    ufw allow 443/tcp comment 'https' > /dev/null
    # Services of yours that have to stay reachable from the internet.
    if [ -n "$ALLOW_PORTS" ]; then
        for p in ${ALLOW_PORTS//,/ }; do
            case "$p" in
                [0-9]*) ufw allow "$p/tcp" comment 'kept open on request' > /dev/null
                        ok "port $p stays open to the internet, as asked" ;;
                *) warn "ignoring \"$p\": not a port number" ;;
            esac
        done
    fi
    if ip link show tailscale0 > /dev/null 2>&1; then
        ufw allow in on tailscale0 comment 'tailscale' > /dev/null
        ok "anything over Tailscale stays allowed, so GitLab and the rest work remotely"
    else
        warn "no tailscale0 here, so only ssh, http and https will be reachable"
    fi
    ufw --force enable > /dev/null || die "ufw would not enable"
    ok "ssh, http and https in; everything else refused from the internet"
    echo
    ufw status verbose | sed 's/^/   /'
    echo
    warn "now closed to the internet but still working locally and over Tailscale:"
    ss -ltnH 2>/dev/null | awk '$4 ~ /^(0\.0\.0\.0|\[::\]|\*):/ {split($4,a,":"); p=a[length(a)];
        if (p != 22 && p != 80 && p != 443) print "     port " p}' | sort -u
}

if [ "$STAGE" = firewall ]; then
    firewall
    exit 0
fi

# ---------------------------------------------------------------- --config: just reinstall it
#
# Reinstalls the nginx config with whichever certificate is already there. Needed because a change
# to the config should never wait on a certificate, and a certificate waits on DNS.
if [ "$STAGE" = config ]; then
    say "the nginx config"
    [ -f /etc/nginx/sites-enabled/evia.am ] || die "nginx is not set up yet; run --stage1 first"
    if [ -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]; then
        install_conf "$HERE/evia.am.conf" /etc/nginx/sites-available/evia.am
        ok "using the Let's Encrypt certificate"
    elif [ -f "$SELF/fullchain.pem" ]; then
        install_conf "$HERE/evia.am.conf" /tmp/evia-conf.$$
        sed "s#/etc/letsencrypt/live/$DOMAIN#$SELF#g" /tmp/evia-conf.$$ > /etc/nginx/sites-available/evia.am
        rm -f /tmp/evia-conf.$$
        ok "using our own certificate; --stage2 replaces it once DNS is ready"
    else
        install_conf "$HERE/evia.am.bootstrap.conf" /etc/nginx/sites-available/evia.am
        ok "no certificate yet, so the http-only config"
    fi
    nginx -t 2> /tmp/evia-nginx-t.log || { cat /tmp/evia-nginx-t.log; die "nginx rejected the config"; }
    systemctl reload nginx || die "nginx would not reload"
    ok "reloaded"
    exit 0
fi

# ---------------------------------------------------------------- --all: the whole thing, once
#
# Runs stage 1, deploys the site, and then stage 2 if the domain is ready - and says so rather than
# failing if it is not, because DNS is the one part nobody here controls.
if [ "$STAGE" = all ]; then
    SELFSIGNED=1
    "$0" --stage1 --selfsigned ${ALLOW_PORTS:+--allow="$ALLOW_PORTS"} || exit 1
    say "the site"
    sudo -u "$OWNER" scripts/deploy-site.sh --downloads || warn "the deploy did not finish"
    ips=$(getent ahostsv4 "$DOMAIN" 2>/dev/null | awk '{print $1}' | sort -u)
    pub=$(curl -fsS -m 10 https://api.ipify.org 2>/dev/null || true)
    if [ -n "$ips" ] && [ -n "$pub" ] && echo "$ips" | grep -qx "$pub"; then
        "$0" --stage2 ${ALLOW_PORTS:+--allow="$ALLOW_PORTS"} || exit 1
    else
        say "the certificate is the one thing left"
        cat <<EOF
   $DOMAIN does not point here yet: it resolves to ${ips:-nothing}, this connection is ${pub:-unknown}.
   The site is already being served - check it now, by address, which needs no DNS:

       http://$(echo "$pub" | sed 's/.*/&/')/

   Add an A record for the bare domain (name "@") pointing at that address, wait for it, then:

       sudo $0 --stage2
EOF
    fi
    exit 0
fi

# ---------------------------------------------------------------- stage 1
if [ "$STAGE" = 1 ]; then
    # First, because this machine may already be on the internet with more open than it should be.
    if [ "$NO_FIREWALL" = 0 ]; then
        firewall
    else
        warn "--no-firewall given: whatever is listening on 0.0.0.0 stays reachable"
    fi

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
    install_conf "$HERE/evia.am.bootstrap.conf" /etc/nginx/sites-available/evia.am
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
            # The public address goes in too, so testing over https by address does not add a
            # second warning about the name not matching on top of the untrusted one.
            san="DNS:$DOMAIN,DNS:www.$DOMAIN,IP:$LAN_IP"
            pub=$(curl -fsS -m 8 https://api.ipify.org 2> /dev/null || true)
            case "$pub" in [0-9]*.[0-9]*.[0-9]*.[0-9]*) san="$san,IP:$pub" ;; esac
            openssl req -x509 -newkey rsa:2048 -nodes -days 365 -sha256 \
                -keyout "$SELF/privkey.pem" -out "$SELF/fullchain.pem" \
                -subj "/CN=$DOMAIN" -addext "subjectAltName=$san" \
                2> /dev/null || die "openssl could not make a certificate"
            chmod 600 "$SELF/privkey.pem"
            ok "made one for $san, valid a year"
        fi
        install_conf "$HERE/evia.am.conf" /tmp/evia-conf.$$
        sed "s#/etc/letsencrypt/live/$DOMAIN#$SELF#g" /tmp/evia-conf.$$ \
            > /etc/nginx/sites-available/evia.am
        rm -f /tmp/evia-conf.$$
        nginx -t 2> /tmp/evia-nginx-t.log || { cat /tmp/evia-nginx-t.log; die "nginx rejected the config"; }
        systemctl reload nginx || die "nginx would not reload"
        ok "HTTPS is up with our own certificate - browsers will warn until stage 2"
        warn "this certificate is NOT trusted by anyone: fine for testing and on this network,"
        warn "not for visitors. Stage 2 replaces it with a real one."
    fi

    say "stage 1 done"
    if private_address "$LAN_IP"; then
        cat <<EOF
   This machine has a private address, so something in front of it decides what reaches it.
   Forward TCP 80 and TCP 443 to $LAN_IP there, and nothing else - not 8081, not 22.
EOF
    else
        cat <<EOF
   This machine is on a public address ($LAN_IP), so nothing has to be forwarded anywhere:
   what it serves is already reachable. Which is also why the firewall above matters.
EOF
    fi
    cat <<EOF

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
    pub=$(curl -fsS -m 10 https://api.ipify.org 2> /dev/null)
    [ -n "$pub" ] || die "cannot work out this connection's public address"

    # Ask only for the names that actually point here. A certificate request fails as a whole if
    # any one name cannot be proved, so including a record that does not exist yet would waste an
    # attempt - and Let's Encrypt limits those.
    names=()
    for n in "$DOMAIN" "www.$DOMAIN"; do
        if getent ahostsv4 "$n" 2> /dev/null | awk '{print $1}' | grep -qx "$pub"; then
            names+=(-d "$n"); ok "$n points here"
        else
            warn "$n does not point here, so it is left out of the request"
        fi
    done
    if [ "${#names[@]}" = 0 ]; then
        die "neither $DOMAIN nor www.$DOMAIN resolves to this connection ($pub).
    Add an A record for the bare domain (name \"@\") pointing at it, wait, and run this again.
    The site is already being served - http://$pub/ works now and needs no DNS."
    fi
    # --cert-name keeps the files under /etc/letsencrypt/live/$DOMAIN whichever names are included,
    # so the config does not have to change when the apex record is added later.
    cert_args=(--cert-name "$DOMAIN" "${names[@]}")

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
                "${cert_args[@]}" \
            || die "certbot failed. Almost always port 80 is not reaching this machine from the
    internet: check the forwarding rule. Its log is in /var/log/letsencrypt/."
        ok "certificate obtained"
    fi
    systemctl list-timers 2>/dev/null | grep -q certbot && ok "renewal timer is active" \
        || warn "no certbot timer; check: systemctl enable --now certbot.timer"

    say "the real config"
    install_conf "$HERE/evia.am.conf" /etc/nginx/sites-available/evia.am
    nginx -t 2> /tmp/evia-nginx-t.log || { cat /tmp/evia-nginx-t.log; die "nginx rejected the config"; }
    systemctl reload nginx || die "nginx would not reload"
    ok "HTTPS on $DOMAIN, www.$DOMAIN and by address; GitLab on localhost and the VPN only"

    if [ "$DEPLOY" = 1 ]; then
        say "the site"
        sudo -u "$OWNER" scripts/deploy-site.sh --downloads || warn "the deploy did not finish"
    fi

    say "stage 2 done"
    for n in "$DOMAIN" "www.$DOMAIN"; do
        getent ahostsv4 "$n" > /dev/null 2>&1 && echo "   https://$n/"
    done
    echo "   Check it from outside your network too - a phone on mobile data is the easiest way."
    exit 0
fi
