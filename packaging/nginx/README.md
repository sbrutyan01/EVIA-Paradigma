# Serving evia.am from the workstation

The site is static files; nginx serves them over HTTPS and GitLab stays private.

## If this machine is directly on a public address

Check first, because it changes what matters:

```bash
ip -4 -o addr show scope global      # is the public address on an interface here?
ss -ltn | grep '0.0.0.0'             # if so, every one of these is on the internet
```

With no router translating anything, there is nothing in front of this machine: SMB on 445, a
message broker on 1883, a GitLab sign-in page on 80 are all open to everyone. `--firewall` (which
`--stage1` runs by default) allows in ssh, http, https and anything over Tailscale, and refuses the
rest from the internet while leaving it working locally and over the VPN. So GitLab, MQTT and file
sharing keep working the way you use them — just not for strangers.

```bash
sudo packaging/nginx/setup.sh --firewall     # on its own, before anything else
```

**The short way** — `setup.sh` does everything below, is safe to run twice, and keeps a copy of
`gitlab.rb` before touching it:

```bash
packaging/nginx/setup.sh --check             # what is done and what is not; needs no sudo
sudo packaging/nginx/setup.sh --stage1       # GitLab off port 80, nginx, the web root
sudo packaging/nginx/setup.sh --stage2 --deploy
```

## Which certificate

Two different things, and only one belongs on a public site.

| | `openssl`, made here | Let's Encrypt, by certbot |
|---|---|---|
| Trusted by browsers | no — a full-page warning for every visitor | yes |
| Costs | nothing | nothing |
| Renews | no, expires in a year, by hand | by itself, every 90 days |
| Good for | testing, and a server only this network reaches | anything the public visits |

So `--stage2` uses Let's Encrypt. `--stage1 --selfsigned` additionally makes an `openssl`
certificate in `/etc/ssl/evia/` and turns HTTPS on with it right away, which is useful before DNS
has settled — you can open `https://<this machine>` on the network
and see the real configuration working, warning and all. Stage 2 then replaces it with a trusted
one, and nothing about the site changes.

Renewal problems are reported to the address in `EMAIL`, which defaults to this repository's git
address. It is the only notice you get if a renewal has been failing, so it is worth having.

Two stages because certbot proves the domain by answering on port 80 from the internet, so the
domain has to resolve here first; everything that does not depend on DNS happens in stage 1.
`--check` before stage 2 will tell you whether the domain actually points here yet — asking for a
certificate before it does wastes one of a limited number of attempts.

The rest of this file is what those stages do, for when something needs doing by hand. Each step
needs `sudo`, because they change how that computer is reachable from the internet.

The shape it ends in:

| Port | Who answers | Reachable from |
|---|---|---|
| 443 | nginx, the site at `evia.am` | the internet |
| 80 | nginx: the ACME path, and a redirect to 443 | the internet |
| 80 | nginx, proxying GitLab by address | this network and Tailscale only |
| 8081 (localhost) | GitLab's own nginx | nothing outside the machine |

## 1. Move GitLab off port 80

It currently listens on `0.0.0.0:80`, which nginx needs. In `/etc/gitlab/gitlab.rb`:

```ruby
external_url 'http://192.168.10.13'
nginx['listen_addresses'] = ['127.0.0.1']
nginx['listen_port'] = 8081
nginx['listen_https'] = false
# GitLab is behind another nginx now, so it must trust what that one tells it
gitlab_rails['trusted_proxies'] = ['127.0.0.1']
```

```bash
sudo gitlab-ctl reconfigure
sudo ss -ltn | grep -E ':(80|8081)'      # 8081 on 127.0.0.1, nothing on 80
```

`external_url` stays the LAN address so existing clone URLs keep working; the proxy passes the
`Host` header through, so GitLab generates the same links it did before.

## 2. nginx and the certificate

```bash
sudo apt install nginx certbot python3-certbot-nginx
sudo mkdir -p /var/www/acme /var/www/evia
sudo chown -R "$USER":"$USER" /var/www/evia
```

`evia.am.conf` names certificate files, and nginx refuses to start when they are not there yet, so
the first config to install is `evia.am.bootstrap.conf` — port 80 only, serving the site in the
clear and carrying the ACME path. Then certbot, then the real config:

```bash
sudo cp packaging/nginx/evia.am.bootstrap.conf /etc/nginx/sites-available/evia.am
sudo ln -sf /etc/nginx/sites-available/evia.am /etc/nginx/sites-enabled/evia.am
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx

# port 80 has to be forwarded by now - certbot proves the domain over it
sudo certbot --nginx -d evia.am -d www.evia.am

sudo cp packaging/nginx/evia.am.conf /etc/nginx/sites-available/evia.am
sudo nginx -t && sudo systemctl reload nginx
```

Certbot renews by itself through `certbot.timer`; check with `systemctl list-timers | grep certbot`.

## 3. Reaching it from outside

Check how this machine is attached, because it decides whether anything else has to be configured:

```bash
ip -4 -o addr show scope global      # is the address here a public one?
curl -s https://api.ipify.org        # what the internet sees
```

**If they are the same**, this machine is on the internet directly: nothing needs forwarding, what
it serves is already reachable, and the firewall in step 0 is the only thing standing between its
services and the world. That is the case this file was written for.

**If the address here is private** (10.x, 172.16–31.x, 192.168.x), something in front translates
it. Forward TCP 80 and TCP 443 to this machine there, and nothing else — not 8081, not 22. GitLab
is on localhost and in a server block limited to the local network, so nothing forwarded reaches
it.

A thing worth knowing either way: a device between your browser and this machine can intercept
those ports and answer in their place. If a page you do not recognise appears, check the
certificate the browser was given — the name on it identifies whatever answered, which is faster
than guessing.


## 4. The site itself

```bash
scripts/deploy-site.sh                 # builds and installs into /var/www/evia
```

Run it whenever the site changes, or let the `site-dell` workflow do it on this machine after a
release. `scripts/deploy-site.sh --downloads` additionally fetches the current release's installers
into `/var/www/evia/downloads/`, which is what the `/downloads/` location in the config serves.

## What is deliberately not here

No PHP, no database, no application code and no uploads: a static site has nothing to exploit, and
that is the point of serving it this way rather than putting a web application on a machine that
holds a CI runner. GitLab remains private. If that ever needs to be public, it needs its own
certificate and its own hard look — not a line in this file.
