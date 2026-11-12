# Deploying MedTrack

This puts MedTrack on a small cloud server with HTTPS, automatic backups and one
command to update. Everything runs in Docker: the Java API, the React app and
Caddy (the web server that gets your HTTPS certificate automatically).

For everything around the install (legal, accounts, first checks, monitoring), follow
[docs/LAUNCH_CHECKLIST.md](docs/LAUNCH_CHECKLIST.md).

To run it without paying for a server or a domain, follow
[docs/FREE_DEPLOY.md](docs/FREE_DEPLOY.md) instead of the install steps below.

## What you need

- A Linux server (VPS): Ubuntu 22.04 or 24.04, 2 GB RAM, in an Indian region.
- A domain name, with DNS **A records** for the domain and `www` pointing to the server.
- A Gmail address with an **App Password** (sends the OTP emails).
- A Google OAuth web client ID (for "Continue with Google"), with
  `https://your-domain` added under **Authorized JavaScript origins**.

## Install

On your PC:

```powershell
.\tests\run-tests.ps1
.\deploy\make-release.ps1
scp .\medtrack-release.zip root@YOUR_SERVER:/opt/
```

The tests must end with `0 failed`. The ZIP leaves out your database and secrets.

On the server:

```bash
apt-get update && apt-get install -y unzip
mkdir -p /opt/medtrack && unzip -o /opt/medtrack-release.zip -d /opt/medtrack
cd /opt/medtrack
sudo bash deploy/install.sh
```

The first run creates `.env` and stops. Fill it in with `nano .env`, then run the
script again. It installs Docker, opens the firewall for web and SSH traffic, builds
and starts everything, waits until the API is healthy, and schedules the nightly
backup copy.

Then open `https://your-domain`. Caddy requests the certificate on the first visit.

```bash
docker compose ps                     # both services should say healthy / running
curl https://your-domain/api/health   # {"status":"ok"}
docker compose logs -f api            # live server log
```

The first lines of the API log say which settings are present:

```text
Email codes (SMTP): set
Google sign-in:     set
Platform admins:    set
Plans:              free for everyone
Google sign-in keys loaded: 2
```

## Updating to a new version

Make a new ZIP, unzip it over the old files on the server, then:

```bash
docker compose up -d --build
```

Your data lives in a Docker volume (`medtrack-data`), so updating does not touch it.
Database changes are applied automatically when the API starts. Take a backup first.

## Free launch

MedTrack is **free for everyone** while `PAID_START` in `.env` is empty. There is no
trial, no banner, no prices on the website and no Plan page. Nothing is ever blocked.

## Starting to charge

The Terms promise at least 30 days' notice, so:

1. Set `PAID_START` to the first paid day, 30 or more days ahead (for example
   `PAID_START=2027-04-01`).
2. Fill in `PAY_UPI_ID`, `SUPPORT_PHONE` and your prices.
3. Run `docker compose up -d`.

From then on:

- Every shop sees "MedTrack is free for N more days", and the **Plan** page appears.
- Nobody is locked out early: all existing shops keep working until `PAID_START`.
- After that day there are `GRACE_DAYS` when everything still works, with a warning.
  New sign-ups get `TRIAL_DAYS` free.
- When that ends, a shop can still read and download all its data, but cannot make bills
  or change anything until it pays.
- The owner pays you by UPI and sends a screenshot. You open **Admin** and press
  **+30 days** or **+1 year**. There is no automatic payment gateway yet.
## Backups

- The API makes a consistent backup every 24 hours and keeps the newest 14, in the
  volume under `backups/`.
- `deploy/backup-offsite.sh` copies them to `/var/backups/medtrack` every night.
- **Neither of those survives losing the server.** Set `RCLONE_REMOTE` in `.env` as
  described at the top of that script, and turn on your provider's server snapshots.
- Shop owners can download their own data at any time from **Activity**.

### Restoring a backup

```bash
docker compose stop api
docker compose cp /var/backups/medtrack/medtrack-YYYYMMDD-HHMMSS.db api:/data/medicine_inventory.db
docker compose start api
```

Practise this once before you need it.

## Moving your current data to the server

Your current database holds test accounts. Starting empty is recommended. To move it
anyway, stop the API on your PC, then copy `src/medicine_inventory.db` into the volume
the same way as a restore. Everyone signs in again afterwards.

## Good to know

- **Time zone:** the API image runs on Indian time, so bill dates and expiry checks are
  correct on a server whose clock is UTC.
- **Sessions** are stored in the database, so a restart does not log anyone out. Pending
  OTP codes are in memory: someone mid-signup may need a new code after a restart.
- **One server only.** SQLite is a single file and is fine for hundreds of shops on one
  server. Running two API servers at once is not supported.
- **Google sign-in** checks tokens on the server using Google's public keys, fetched
  hourly, so a sign-in does not depend on reaching Google at that moment.
- **Gmail limit:** about 500 emails a day. Move to a mail service before you reach it.
- **Rate limits** use the visitor's real IP from Caddy (`TRUST_PROXY=true` is set in the
  Docker image). Never expose port 8080 directly when that is on.
- **Trying Docker on your PC:** put `DOMAIN=localhost` in `.env` and open
  `https://localhost` (the browser warns about the local certificate).

## Settings reference (environment variables)

| Name | Default | Meaning |
|---|---|---|
| `DOMAIN` | none | Your website address |
| `SMTP_EMAIL`, `SMTP_APP_PASSWORD` | none | Gmail account that sends OTP emails |
| `GOOGLE_CLIENT_ID` | none | Google sign-in client ID |
| `ADMIN_EMAILS` | none | Who can open the Admin page |
| `SUPPORT_PHONE`, `SUPPORT_EMAIL` | none | Shown to customers |
| `PAY_UPI_ID`, `PAY_NOTE` | none | Shown on the Plan page once charging is announced |
| `PLAN_PRICE_MONTHLY`, `PLAN_PRICE_YEARLY` | 499, 4999 | Prices in rupees, used once charging is announced |
| `TRIAL_DAYS`, `GRACE_DAYS` | 14, 3 | Used once charging starts |
| `PAID_START` | empty | First paid day (YYYY-MM-DD). Empty = free for everyone |
| `PORT` | 8080 | API port |
| `DB_PATH` | next to the project | Database file location |
| `THREADS` | 16 | Request worker threads |
| `ALLOWED_ORIGINS` | local dev sites | Sites allowed to call the API from another domain |
| `TRUST_PROXY` | false | Read visitor IP from `X-Forwarded-For` |
| `MAX_BODY_BYTES` | 2097152 | Largest request accepted |
| `RATE_PER_MINUTE` | 3000 | Requests per minute from one address |
| `BACKUP_ENABLED` | true | Turn automatic backups on or off |
| `BACKUP_DIR` | `backups` beside the database | Where backups go |
| `BACKUP_INTERVAL_HOURS` | 24 | Hours between backups |
| `BACKUP_KEEP` | 14 | Backups to keep |

## Running on your own Windows PC (development)

1. Copy `server.env.example` to `server.env` and fill it in (once).
2. Start the API with `.\run-local.ps1`.
3. In `frontend`, run `npm run dev`.
