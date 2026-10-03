# Deployment Runbook — AIIMS Kalyani IT Event Portal

Docker Compose deployment. Apache stays on the host as a reverse proxy.

---

## Architecture

```
Internet
   │  :80 / :443
   ▼
Apache (host) ──mod_proxy──►  aiims-app container ──JDBC──►  aiims-db container
   │                           127.0.0.1:8080                 MySQL 8.0.46
   │                           /app/uploads (bind mount)
   └── TLS, vhost, access logs
```

**Where the data lives — this is the key thing to understand:**

| Data | Lives in | Survives rebuild? | Survives `down`? |
|---|---|---|---|
| MySQL (events, users, images rows) | named volume `aiims_db_data` | ✅ | ✅ |
| Uploaded images (106 MB) | bind mount `./uploads` on the host | ✅ | ✅ |
| Application code | image `aiims-app:<tag>` | ❌ rebuilt each deploy | ✅ |

The application code lives **inside an image**, which is disposable. The database
lives **outside the image**, in a volume the app container has no control over.
So rebuilding and restarting the app can never delete data.

### ⚠️ Commands that DO destroy data

| Command | Result |
|---|---|
| `docker compose down -v` | 🔴 **Deletes the database volume. All data gone.** |
| `docker volume rm aiims_db_data` | 🔴 Gone permanently |
| `docker system prune -a --volumes` | 🔴 Dangerous while `db` is stopped |
| `rm -rf` the project dir, recreate under a new name | 🟠 Volume orphaned (mitigated: volume name is pinned) |

Safe: `docker compose down` (no `-v`), `restart`, `up -d --build`, `logs`,
`ps`, `pull`, `rm <app-container>`.

---

## Prerequisites on the server (Ubuntu/Debian)

Disk: **~3 GB free** (MySQL image + app image + data). RAM: **2 GB** minimum
for build + runtime.

Install Docker (one-time):

```bash
sudo apt update && sudo apt install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker $USER      # then log out and back in
docker --version && docker compose version
```

> Java is **not** installed on the host — Temurin 25 ships inside the image.

---

## First-time deployment

### Step 1 — Get the code onto the server

```bash
sudo mkdir -p /opt/aiims
sudo chown "$USER":"$USER" /opt/aiims
cd /opt/aiims
git clone https://github.com/GeekAyan/it.git .
chmod +x deploy/*.sh          # git may not preserve the executable bit
```

### Step 2 — Create the secrets file

```bash
cp .env.example .env
nano .env                    # fill MYSQL_ROOT_PASSWORD, DB_PASSWORD, MAIL_*, APP_TAG
chmod 600 .env
```

`MAIL_PASSWORD` must be a **Google App Password**, not the account password.

### Step 3 — Place the uploads folder

`uploads/` is git-ignored, so `git clone` does **not** bring it. Copy it across
from the Windows machine, then fix ownership — the container runs as uid 10001
and must be able to write new files.

```bash
# on Windows
scp -r 'c:\Users\ADMIN\Documents\Work\AIIMS\VS Code workspace\it\uploads' USER@SERVER:/opt/aiims/uploads

# on the server
cd /opt/aiims
sudo chown -R 10001:10001 uploads
```

### Step 4 — Start MySQL and import the existing data

Export from Windows first (use `--result-file`, **not** `>`, which PowerShell 5.1
writes as UTF-16 and corrupts the dump):

```powershell
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe' -u root -p `
  --single-transaction --routines --triggers --set-gtid-purged=OFF `
  --default-character-set=utf8mb4 --result-file="$env:TEMP\eventdb.sql" eventdb
scp "$env:TEMP\eventdb.sql" USER@SERVER:/opt/aiims/backups/
```

Then, on the server:

```bash
cd /opt/aiims
mkdir -p backups
docker compose up -d db
docker compose exec -T db sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" eventdb' < backups/eventdb.sql
docker compose exec -T db sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "TRUNCATE eventdb.otp_codes;"'
```

Verify the import — these numbers must match the source database
(11 / 11 / 6 / 1):

```bash
docker compose exec -T db sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "
  SELECT \"events\",       COUNT(*) FROM eventdb.events
  UNION ALL SELECT \"event_images\", COUNT(*) FROM eventdb.event_images
  UNION ALL SELECT \"users\",        COUNT(*) FROM eventdb.users
  UNION ALL SELECT \"admin_users\",  COUNT(*) FROM eventdb.admin_users;"'
```

> The dump includes the legacy `event` table, which is still present in the
> schema even though no entity maps to it any more. That is intentional.

### Step 5 — Start the app

```bash
docker compose up -d --build app
docker compose logs -f --tail=100 app      # wait for "Started ItApplication"
```

### Step 6 — Apache

```bash
sudo cp deploy/aiims.apache.conf /etc/apache2/sites-available/aiims.conf
sudo a2enmod proxy proxy_http headers
sudo a2ensite aiims.conf
sudo apachectl configtest && sudo systemctl reload apache2
```

Edit `ServerName` in the copied file first if your hostname differs.

### Step 7 — Verify

```bash
curl -I http://127.0.0.1:8080/login     # from the server  -> 200
curl -I http://127.0.0.1/login          # via Apache       -> 200
docker compose ps                      # both services "healthy"/"running"
```

Then in a browser: log in, request an OTP (tests SMTP), submit an event with an
image, view and download that image, run the admin Excel export, and try
"download all images".

### Step 8 — HTTPS (recommended)

```bash
sudo apt install -y certbot python3-certbot-apache
sudo certbot --apache -d events.example.edu.in
```

---

## Daily operations

### Deploying a code update

Edit on your Windows machine, commit, push — then on the server:

```bash
cd /opt/aiims
./deploy/deploy.sh
```

That script backs up the database, backs up `uploads/`, runs `git pull`, rebuilds
the image and restarts **only the app container**. The database container is
never restarted and the volume is never touched.

> **Never edit code inside a running container.** `docker exec` edits vanish the
> moment the container is recreated. All changes must go through the repository.

### Config-only change (passwords, heap size, tags)

```bash
nano .env
docker compose up -d app     # recreates the app container, ~5 seconds, no rebuild
```

### Using a prebuilt image from ghcr.io (optional, recommended)

The GitHub Actions workflow publishes every merge to `main` as
`ghcr.io/geekayan/it`. Using it removes the need for source code and build RAM
on the server, and cuts a deploy from minutes to seconds.

> The image name is **all lowercase** (`geekayan`), not `GeekAyan`. Container
> registries reject uppercase paths.

**One-time setup.**

1. Create a token: GitHub → Settings → Developer settings → Personal access
   tokens → *Tokens (classic)* → generate with the **`read:packages`** scope.
2. Log the server in to the registry (skip only if the package is public):

   ```bash
   echo "<YOUR_TOKEN>" | docker login ghcr.io -u <your-github-username> --password-stdin
   ```

3. Switch the `app` service in `docker-compose.yml` from build mode to pull
   mode — comment out the `build:` block and point `image:` at the registry:

   ```yaml
   app:
     # build:            <-- comment this whole block out
     #   context: .
     #   dockerfile: Dockerfile
     image: ghcr.io/geekayan/it:latest
   ```

4. Pull and start:

   ```bash
   docker compose pull app
   docker compose up -d app
   ```

**Deploying afterwards** becomes two commands — no `git pull`, no rebuild:

```bash
cd /opt/aiims
docker compose pull app && docker compose up -d app
```

> `deploy.sh` assumes **build** mode (it runs `git pull` + `docker compose
> build`). In pull mode use the two commands above instead, or adjust
> `deploy.sh` to drop the build step.

**Pinning a specific release** is the rollback mechanism: replace `:latest`
with the commit SHA shown in the workflow run summary, then `up -d`.

### Rollback

```bash
./deploy/rollback.sh <previous-commit-sha>
```

### Backups

`./deploy/deploy.sh` backs up automatically on every deploy and keeps the last 10.
To back up manually:

```bash
cd /opt/aiims
docker compose exec -T db sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --single-transaction --routines --triggers --default-character-set=utf8mb4 eventdb' \
  > "backups/eventdb-$(date +%Y%m%d-%H%M%S).sql"
tar -czf "backups/uploads-$(date +%Y%m%d-%H%M%S).tar.gz" uploads
```

Copy `backups/` off the server regularly — a backup that only exists on the same
box is not a backup.

### Restore

```bash
./deploy/restore.sh                                   # lists available backups
./deploy/restore.sh backups/eventdb-20261003-120000.sql
```

### Logs

```bash
docker compose logs -f --tail=100 app
docker compose logs -f --tail=100 db
sudo tail -f /var/log/apache2/aiims-error.log
```

Both services cap their logs at 10 MB × 3 files, so logs cannot fill the disk.

### Moving to a different server

```bash
# 1. install Docker on the new host (see Prerequisites)
# 2. copy these across:
#      Dockerfile  .dockerignore  docker-compose.yml  .env  pom.xml  src/  uploads/
# 3. then:
cd /opt/aiims
mkdir -p backups && docker compose up -d db
docker compose exec -T db sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" eventdb' < backups/eventdb-<ts>.sql
docker compose up -d --build app
sudo cp deploy/aiims.apache.conf /etc/apache2/sites-available/aiims.conf
sudo a2ensite aiims.conf && sudo apachectl configtest && sudo systemctl reload apache2
```

With the GitHub Actions workflow enabled, the server needs **no source code** at
all — just the image from `ghcr.io`.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Images load as broken icons | `uploads/` ownership wrong | `sudo chown -R 10001:10001 uploads` |
| `403` on every page after Apache setup | App not running | `docker compose ps`, `docker compose logs app` |
| App restarts in a loop, "Communications link failure" | App started before MySQL was ready | Already handled by `depends_on: service_healthy`; check `docker compose logs db` |
| Large image upload hangs then fails | Apache `ProxyTimeout` too low | It is set to 300s in the vhost; confirm with `apachectl configtest` |
| `Access denied for user 'aiims'` | Credentials mismatch | Check `DB_PASSWORD` in `.env` matches the `aiims` user in MySQL |
| OTPs never arrive | Wrong mail credentials | `MAIL_PASSWORD` must be a Google **App Password** |
| Build fails on `Tests run: ...` | Missing `-DskipTests` | Already set in the Dockerfile |
| `no space left on device` | Disk full | `docker system df`, then prune images: `docker image prune -a` |

---

## Notes specific to this application

- **`ddl-auto=update` is additive only.** Hibernate adds missing tables/columns
  and never drops or deletes anything, so deploying new code cannot lose rows.
  It also never *removes* a column, so renaming an entity field leaves the old
  column behind (harmless; clean up manually if you care). Never switch to
  `create-drop` — that wipes the database on every boot.
- **Sessions are in-memory.** There is no Spring Session, so every restart logs
  all users out. Do not add a second app container without sticky sessions.
- **Production logging is overridden via environment variables** in
  `docker-compose.yml` (`show-sql=false`, Thymeleaf cache on, DEBUG logging off).
  Change these in the compose file — no rebuild needed.
- **The `uploads` path is relative** (`file.upload-dir=uploads`) and resolves
  against the container's working directory, which is why `/app` is set as
  `WORKDIR` and `/app/uploads` is bind-mounted.
- **`EventController` hardcodes `MediaType.IMAGE_JPEG`** while PNG files exist
  in `uploads/`. Pre-existing bug, unrelated to deployment; browsers usually
  cope, but it should be fixed by detecting the real content type.