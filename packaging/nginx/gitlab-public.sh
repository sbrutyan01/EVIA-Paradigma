#!/usr/bin/env bash
# Makes GitLab reachable from the internet at https://git.evia.am. Run it with sudo, on the machine
# itself, after setup.sh has put nginx in front of GitLab. Running it again is safe.
#
#   sudo packaging/nginx/gitlab-public.sh
#
#   GIT_DOMAIN=...   the name (default git.evia.am)
#   EMAIL=...        where a failing certificate renewal is reported (default: this repository's git address)
#
# In order, stopping at the first thing that fails:
#   1. the name has to point at this machine already (an A record at the DNS provider);
#   2. the name leaves the private http://dell block and gets a server block of its own;
#   3. a Let's Encrypt certificate for it, renewed by certbot's timer, nginx reloaded after each;
#   4. GitLab's own address becomes https://git.evia.am, so its links, clone URLs and emails use it.
#
# What it does NOT do: change who may sign in. That is GitLab's job now - keep sign-ups off and
# turn on two-factor sign-in for root (User settings > Account).
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GIT="${GIT_DOMAIN:-git.evia.am}"
OWNER="${SUDO_USER:-$(id -un)}"
EMAIL="${EMAIL:-$(sudo -u "$OWNER" git -C "$HERE" config user.email 2>/dev/null || true)}"
SITE=/etc/nginx/sites-available/evia.am
CONF=/etc/nginx/sites-available/$GIT
RB=/etc/gitlab/gitlab.rb

say()  { printf '\n\033[1m== %s\033[0m\n' "$1"; }
ok()   { printf '   \033[32mok\033[0m    %s\n' "$1"; }
warn() { printf '   \033[33mnote\033[0m  %s\n' "$1"; }
die()  { printf '\n\033[31mgitlab-public: %s\033[0m\n' "$1" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "run this with sudo"
[ -f "$SITE" ] || die "$SITE is missing: run packaging/nginx/setup.sh first"
command -v certbot > /dev/null || die "certbot is not installed: run packaging/nginx/setup.sh --stage1 first"
ss -ltn | grep -q '127.0.0.1:8081' || die "GitLab is not on 127.0.0.1:8081: run packaging/nginx/setup.sh --stage1 first"

reload() {
    nginx -t 2> /tmp/evia-nginx-t.log || { cat /tmp/evia-nginx-t.log; die "nginx rejected the config"; }
    systemctl reload nginx || die "nginx would not reload"
}

# ---- 1. DNS
say "$GIT"
pub=$(curl -fsS -m 10 https://api.ipify.org 2> /dev/null) || die "cannot work out this connection's public address"
ips=$(getent ahostsv4 "$GIT" 2> /dev/null | awk '{print $1}' | sort -u | tr '\n' ' ')
echo "$ips" | grep -qw "$pub" || die "$GIT resolves to '${ips:-nothing}', not to this machine ($pub).
    Add an A record at the DNS provider: name \"git\", type A, value $pub. Wait for it, then run this again."
ok "$GIT points here ($pub)"

# ---- 2. nginx: its own server block, first without the certificate it does not have yet
say "nginx"
if grep -qE "server_name .*dell.*\b$GIT\b" "$SITE"; then
    cp -a "$SITE" "$SITE.before-git-$(date +%Y%m%d%H%M%S)"
    sed -i -E "/server_name .*dell/ s/ +${GIT//./\\.}\b//" "$SITE"
    ok "$GIT taken out of the private http://dell block"
else
    ok "the private block does not name $GIT"
fi
if [ ! -f "/etc/letsencrypt/live/$GIT/fullchain.pem" ]; then
    # the port-80 half alone: nginx refuses a config naming a certificate that is not there
    awk '/^server \{/ { n++ } n <= 1' "$HERE/git.evia.am.conf" | sed "s/git\.evia\.am/$GIT/g" > "$CONF"
    ln -sf "$CONF" "/etc/nginx/sites-enabled/$GIT"
    reload
    ok "port 80 for $GIT, for the certificate check"
fi

# ---- 3. the certificate
say "the certificate"
if [ -f "/etc/letsencrypt/live/$GIT/fullchain.pem" ]; then
    ok "already have one; certbot renews it by itself"
else
    if [ -n "$EMAIL" ]; then reg=(-m "$EMAIL"); else reg=(--register-unsafely-without-email); fi
    certbot certonly --webroot -w /var/www/acme --cert-name "$GIT" -d "$GIT" \
            --non-interactive --agree-tos --no-eff-email "${reg[@]}" \
            --deploy-hook "systemctl reload nginx" \
        || die "certbot failed; its log is in /var/log/letsencrypt/. Is port 80 open to the internet?"
    ok "certificate obtained${EMAIL:+, renewal problems go to $EMAIL}"
fi
sed "s/git\.evia\.am/$GIT/g" "$HERE/git.evia.am.conf" > "$CONF"
ln -sf "$CONF" "/etc/nginx/sites-enabled/$GIT"
reload
ok "https://$GIT is served"

# ---- 4. GitLab's own address
say "GitLab"
if grep -qE "^external_url ['\"]https://$GIT['\"]" "$RB"; then
    ok "external_url is already https://$GIT"
else
    cp -a "$RB" "$RB.before-git-$(date +%Y%m%d%H%M%S)"
    ok "kept a copy of gitlab.rb"
    # comment out what is about to be replaced, then add ours
    sed -i -E "s/^[[:space:]]*(external_url|letsencrypt\['enable'\]|nginx\['proxy_set_headers'\])/# &/" "$RB"
    cat >> "$RB" <<EOF

# Added by packaging/nginx/gitlab-public.sh: GitLab is public at https://$GIT. The system nginx
# holds the certificate and passes plain http to GitLab on 127.0.0.1:8081 (set by setup.sh).
external_url 'https://$GIT'
letsencrypt['enable'] = false
nginx['proxy_set_headers'] = { "X-Forwarded-Proto" => "https", "X-Forwarded-Ssl" => "on" }
EOF
    ok "gitlab.rb updated"
    echo "   reconfiguring GitLab (a few minutes)..."
    gitlab-ctl reconfigure > /tmp/evia-gitlab-reconfigure.log 2>&1 \
        || die "gitlab-ctl reconfigure failed; see /tmp/evia-gitlab-reconfigure.log"
    ok "gitlab-ctl reconfigure done"
fi
ss -ltn | grep -q '127.0.0.1:8081' || warn "nothing on 127.0.0.1:8081 now - check: sudo gitlab-ctl status"

say "check"
for i in $(seq 1 30); do
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "https://$GIT/users/sign_in")
    [ "$code" = 200 ] && break
    sleep 5
done
[ "$code" = 200 ] && ok "https://$GIT/users/sign_in answers" || warn "https://$GIT/users/sign_in answered $code"
cat <<EOF

   Done. Still worth doing by hand:
     - two-factor sign-in for root: https://$GIT/-/profile/two_factor_auth
     - git remotes: git remote set-url gitlab https://$GIT/evia/evia-paradigma.git
EOF
