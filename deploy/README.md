# Deployment Runbook — AIIMS Kalyani IT Event Portal

Native systemd deployment. **No Docker, no container runtime, and no package
manager on the server.** Apache on the host reverse-proxies to a plain JVM.

---

## Architecture

```
Internet
   │  :80 / :443
   ▼
Apache (host, already present) ──mod_proxy──►  Spring Boot JVM
   │   2.4.66                                   aiims-app.service
   │   :8080 (127.0.0.1 only)                   /opt/java  (Temurin JRE 25)
   `--------------------------------------------------------------------------+
                                                      │
                                                      │ JDBC 127.0.0.1:3307
                                                      ▼
                                            aiims-mysql.service
                                            MySQL 8.4, datadir /opt/aiims/mysql/data
```
**The app is mounted at `/event`, not `/`.** This Apache already serves
`/network_monitor`, `/mrtg`, `/gestioip` and `/switch-live` on port 80, so the
app takes only the `/event` prefix and runs with
`server.servlet.context-path=/event`.

| URL | Serves |
|---|---|
| `http://172.16.98.235/event/` | this application |
| `http://172.16.98.235/network_monitor` | pre-existing monitoring app |
| `http://172.16.98.235/mrtg` | pre-existing MRTG graphs |
| `http://172.16.98.235/gestioip` | pre-existing GestioIP |
| `http://172.16.98.235/switch-live` | pre-existing LAN monitor |

### Why a second MySQL instance

The host already runs a **production MySQL 8.4.11 on port 3306** that serves
other applications. This deployment creates a completely separate instance on
**port 3307**, reusing the same `mysqld` binary but with its own datadir,
socket, PID file and systemd service. The 3306 instance is never referenced,
stopped or modified.

| | Production instance | This app's instance |
|---|---|---|
| Port | 3306 | **3307** |
| Datadir | `/var/lib/mysql` | `/opt/aiims/mysql/data` |
| Service | `mysql.service` | `aiims-mysql.service` |
| Remove it | never | `systemctl disable --now aiims-mysql` |

### Where the data lives

| Data | Location | Survives a deploy? |
|---|---|---|
| Events, users, image rows | `/opt/aiims/mysql/data` (port 3307) | ✅ |
| Uploaded images | `/opt/aiims/uploads` | ✅ |
| Application jar | `/opt/aiims/app.jar` | ❌ replaced each deploy |

A deploy replaces only the jar. **Nothing deletes the data**, and
`deploy.sh` takes a full backup before touching anything anyway.

---

## Prerequisites

Disk: ~500 MB. RAM: ~700 MB total (JVM 256–768 MB + MySQL ~400 MB).
Root access via SSH. Java is **not** needed on the host — see Step 2.

---

## First-time deployment

### Step 1 — Get the code onto the server
```bash
mkdir -p /opt/aiims
cd /opt/aiims
git clone https://github.com/GeekAyan/it.git .
chmod +x deploy/*.sh
```

### Step 2 — Install the Java runtime (no apt)
```bash
mkdir -p /opt/java
curl -fsSL -o /tmp/jre.tar.gz \
  'https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jre/hotspot/normal/eclipse'
tar -xzf /tmp/jre.tar.gz -C /opt/java --strip-components=1
/opt/java/bin/java -version
```

### Step 3 — Create the service account and directories
```bash
useradd -r -m -d /opt/aiims -s /usr/sbin/nologin aiims 2>/dev/null || true
mkdir -p /opt/aiims/{uploads,logs,backups,mysql}
chown -R aiims:aiims /opt/aiims
```

### Step 4 — Configure
```bash
cd /opt/aiims
cp deploy/aiims.env.example           aiims.env && chmod 600 aiims.env
cp deploy/application-prod.properties application-prod.properties
cp deploy/aiims-mysql.service         /etc/systemd/system/
cp deploy/aiims-app.service           /etc/systemd/system/
nano aiims.env          # set DB_PASSWORD, MAIL_USERNAME, MAIL_PASSWORD
```
Generate a strong `DB_PASSWORD` — MySQL 8.4's `validate_password` rejects weak
ones. The same password is used for the app and the database, so they cannot
drift apart.

> The MySQL config is **not** copied here — `init-db.sh` installs it to
> `/etc/mysql/aiims.cnf` in Step 7, because that is where AppArmor expects it.

#### Why `/etc/mysql` and not `/opt/aiims`

Ubuntu confines `mysqld` with an AppArmor profile that only permits reading
`/etc/mysql/**` and writing `/var/lib/mysql/**`. A config under `/opt` is
rejected outright:

```
mysqld: [ERROR] Failed to open required defaults file: .../aiims-mysql.cnf
```

`init-db.sh` handles both halves automatically:
1. installs the config to `/etc/mysql/aiims.cnf`
2. writes a narrowly-scoped local override to
   `/etc/apparmor.d/local/usr.sbin.mysqld` granting access to
   `/opt/aiims/mysql/**`, then reloads the profile

To inspect what the confinement allows: `cat /etc/apparmor.d/usr.sbin.mysqld`.

### Step 5 — Build the jar (on your Windows machine)
Build from a **clean clone** so `application-local.properties` (which holds
plaintext credentials) is not baked into the jar:
```powershell
git clone https://github.com/GeekAyan/it.git C:\temp\aiims-build
cd C:\temp\aiims-build
.\mvnw.cmd -DskipTests package
```
`-DskipTests` is mandatory: `ItApplicationTests` is a `@SpringBootTest` that
boots the whole Spring context and needs a live database.

### Step 6 — Transfer everything
```powershell
scp C:\temp\aiims-build\target\it-0.0.1-SNAPSHOT.jar `
    root@172.16.98.235:/tmp/
scp C:\temp\transfer\eventdb.sql C:\temp\transfer\uploads.tar.gz `
    root@172.16.98.235:/tmp/
```
```bash
tar -xzf /tmp/uploads.tar.gz -C /opt/aiims     # yields /opt/aiims/uploads
chown -R aiims:aiims /opt/aiims/uploads
install -m 640 -o aiims -g aiims /tmp/it-0.0.1-SNAPSHOT.jar /opt/aiims/app.jar
```

### Step 7 — Initialise the database
```bash
sudo /opt/aiims/deploy/init-db.sh /tmp/eventdb.sql
```
This initialises a fresh datadir, starts `aiims-mysql.service`, sets the root
password, creates `eventdb` plus the `aiims` user, and imports the dump. It
refuses to run twice.

### Step 8 — Start the application
```bash
systemctl daemon-reload
systemctl enable --now aiims-app.service
journalctl -u aiims-app -f
```

### Step 9 — Apache reverse proxy (scoped to /event)

The app must **not** claim the whole virtual host: this Apache already serves
`/network_monitor`, `/mrtg`, `/gestioip` and `/switch-live` on port 80. Adding a
`<VirtualHost>` with a `ServerAlias` for the server's IP claims *every* request
and breaks all of them.

Instead, insert the `/event` block **inside the existing default vhost**:

```bash
a2enmod proxy proxy_http headers

# back up the existing vhost first
sudo cp -a /etc/apache2/sites-available/000-default.conf \
            /etc/apache2/sites-available/000-default.conf.bak-$(date +%Y%m%d-%H%M%S)

# insert the snippet just before the closing </VirtualHost>
sudo awk '/<\/VirtualHost>/ && !d {
            while ((getline l < "/opt/aiims/deploy/event-proxy.conf") > 0) print l
            d=1
        } { print }' \
    /etc/apache2/sites-available/000-default.conf > /tmp/dd.conf
sudo install -m 644 /tmp/dd.conf /etc/apache2/sites-available/000-default.conf

sudo apachectl configtest && sudo systemctl reload apache2
```

`a2enmod proxy` only enables a module already part of the installed `apache2`
package — no package installation is involved.

### Step 10 — Verify
```bash
curl -I http://127.0.0.1:8080/event/login    # app directly -> 200
curl -I http://127.0.0.1/event/login         # via Apache  -> 200

# the other applications on this host must still work
for p in / /network_monitor /mrtg /gestioip /switch-live; do
  printf "%-18s %s\n" "$p" \
    "$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: 172.16.98.235' http://127.0.0.1$p)"
done

systemctl status aiims-app aiims-mysql
ss -lnt | grep 3307
```
Browser: log in, request an OTP (tests SMTP), submit an event with an image,
view and download it, run the admin Excel export, try "download all images".

### Step 11 — HTTPS (recommended)

```bash
certbot --apache -d your-hostname
```

Certbot rewrites the vhost in place. `server.forward-headers-strategy=framework`
then makes Spring Security set the `Secure` cookie flag correctly.

---

## Daily operations

### Deploying a code update

Build and send the new jar, then run the deploy script:

```powershell
cd C:\temp\aiims-build
git pull && .\mvnw.cmd -DskipTests package
scp target\it-0.0.1-SNAPSHOT.jar root@172.16.98.235:/tmp/
```
```bash
sudo /opt/aiims/deploy/deploy.sh /tmp/it-0.0.1-SNAPSHOT.jar
```

`deploy.sh` backs up the database, uploads and the current jar, installs the
new one, restarts the service, then **health-checks it. If the app does not
become healthy within 60 seconds it automatically rolls back** the jar and
leaves the database untouched.

The database service is never restarted by a deploy.

### Config-only change (passwords, heap size)

```bash
nano /opt/aiims/aiims.env     # or application-prod.properties
systemctl restart aiims-app
```
No rebuild, ~20 seconds.

### Rollback

```bash
sudo /opt/aiims/deploy/rollback.sh                        # previous jar
sudo /opt/aiims/deploy/rollback.sh app-20261003-120000.jar
```
Swaps the jar and restarts. The database is not involved.

### Backups

`deploy.sh` backs up automatically before every deploy and keeps the last 10.
To do it by hand:
```bash
cd /opt/aiims
source <(grep '^DB_PASSWORD=' aiims.env)
mysqldump --defaults-file=mysql/aiims-mysql.cnf -u root -p"$DB_PASSWORD" \
  --single-transaction --routines --triggers --default-character-set=utf8mb4 \
  eventdb > "backups/eventdb-$(date +%Y%m%d-%H%M%S).sql"
tar -czf "backups/uploads-$(date +%Y%m%d-%H%M%S).tar.gz" uploads
```

Copy `backups/` off the server — a backup that only exists on the same machine
is not a backup.

### Restore

```bash
sudo /opt/aiims/deploy/restore.sh                                 # list backups
sudo /opt/aiims/deploy/restore.sh backups/eventdb-20261003-120000.sql
```
Takes a safety dump of the current state first, then confirms before replacing.

### Logs

```bash
journalctl -u aiims-app -f                 # application
journalctl -u aiims-mysql -f               # database
tail -f /var/log/apache2/aiims-error.log   # web server
tail -f /opt/aiims/mysql/error.log         # database error log
```

### Stopping / starting the stack

```bash
systemctl stop  aiims-app          # app only; database keeps running
systemctl stop  aiims-app aiims-mysql
systemctl start aiims-app
```

### Moving to a different server

```bash
# 1. create the aiims user, /opt/aiims tree and /opt/java (Steps 2-3)
# 2. copy across: aiims.env, application-prod.properties, app.jar, uploads/
# 3. then:
sudo /opt/aiims/deploy/init-db.sh /tmp/eventdb.sql
systemctl daemon-reload && systemctl enable --now aiims-app
# add the /event block to the existing default vhost - see Step 9
sudo awk '/<\/VirtualHost>/ && !d {
            while ((getline l < "/opt/aiims/deploy/event-proxy.conf") > 0) print l
            d=1
        } { print }' \
    /etc/apache2/sites-available/000-default.conf > /tmp/dd.conf
sudo install -m 644 /tmp/dd.conf /etc/apache2/sites-available/000-default.conf
sudo apachectl configtest && sudo systemctl reload apache2
```
No build toolchain, no container runtime, no package manager needed on the
destination.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Images show as broken | `uploads/` ownership | `chown -R aiims:aiims /opt/aiims/uploads` |
| App restarts in a loop | Cannot reach MySQL on 3307 | `systemctl status aiims-mysql`, check `/opt/aiims/mysql/error.log` |
| `Communications link failure` | `allowPublicKeyRetrieval` missing from `DB_URL` | check `aiims.env` |
| `Access denied for user 'aiims'` | password mismatch | `DB_PASSWORD` in `aiims.env` must match the MySQL user |
| OTP emails never arrive | wrong SMTP credentials | `MAIL_PASSWORD` must be a Google **App Password** |
| Every page 503 through Apache | app not listening | `ss -lnt \| grep 8080`, `journalctl -u aiims-app` |
| Large upload times out | Apache `ProxyTimeout` too low | set to 300 in the vhost; re-run `apachectl configtest` |
| `unit not found` | units not installed | `cp deploy/*.service /etc/systemd/system/ && systemctl daemon-reload` |
| `mysqld` refuses to start | datadir perms | `chown -R aiims:aiims /opt/aiims/mysql` |

---

## Notes specific to this application

- **`ddl-auto=update` is additive only.** Hibernate adds missing tables and
  columns and never drops or deletes anything, so deploying new code cannot
  lose rows. It also never *removes* a column, so renaming an entity field
  leaves the old column behind (harmless). Never switch to `create-drop`.
- **`WorkingDirectory=/opt/aiims` is load-bearing.** `file.upload-dir=uploads`
  is a *relative* path that resolves against it. Get this wrong and every
  image 404s.
- **The app runs under the `/event` context path.** All templates use Thymeleaf
  `@{/...}` and the success handler uses `request.getContextPath()`, so links
  stay under `/event` automatically. If you ever mount it somewhere else,
  change `server.servlet.context-path` **and** the `ProxyPass` in
  `deploy/event-proxy.conf` together.
- **Never give this app its own `<VirtualHost>` with a `ServerAlias` for the
  server's IP.** That claims every request for that host and silently breaks
  `/network_monitor`, `/mrtg`, `/gestioip` and `/switch-live`.
- **Sessions are in-memory** — there is no Spring Session, so every restart
  logs all users out. Fine for a single instance; do not run two without
  sticky sessions.
- **Secrets never enter the jar.** `application-local.properties` is git-ignored,
  so building from a clean clone keeps the DB and Gmail passwords out of the
  artifact. They are supplied by `/opt/aiims/aiims.env` at runtime.
- **Rotate the DB and Gmail passwords.** Both were previously stored in
  plaintext in the working copy of the repository.
- **`EventController` hardcodes `MediaType.IMAGE_JPEG`** while PNG files exist
  in `uploads/`. Pre-existing bug, unrelated to deployment; browsers usually
  cope with it.
- **Docker is installed on the host but unused.** It can be removed with
  `apt purge docker-ce docker-ce-cli containerd.io` if you want the host clean;
  nothing in this deployment depends on it.
