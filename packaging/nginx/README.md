# Serving evia.am from the workstation

The site is static files; nginx serves them over HTTPS and GitLab stays private. Everything below
is done once, on the machine itself, with `sudo` — which is why it is written down rather than
automated: these steps change how that computer is reachable from the internet.

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

## 3. The router

Forward **only** these, to `192.168.10.13`:

| Protocol | Port | Why |
|---|---|---|
| TCP | 443 | the site |
| TCP | 80 | the ACME challenge for renewals, and the redirect to HTTPS |

Do **not** forward 8081, 22, or anything else. GitLab is on localhost and the LAN-only server
block, so nothing forwarded here reaches it.

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
