# Launch checklist

Work through this from top to bottom. Items marked **you** cannot be done by the
software or by a developer on your behalf.

## Before the first customer

### Business and legal (you)

- [ ] Ask a CA to confirm GST rates and HSN codes for your medicines, and how
      invoices must be numbered. GST rates on medicines can change: check the
      current notification, and set the correct rate on each medicine.
- [ ] Ask a lawyer to review `frontend/public/terms.html` and `privacy.html`.
      They are written as sensible drafts, not legal advice. Add your legal
      business name and address.

### Accounts and server (you)

- [ ] Get a domain and a Linux server (Ubuntu 22.04 or 24.04, 2 GB RAM, in an Indian
      region). To do this for free, follow `docs/FREE_DEPLOY.md`.
- [ ] Add a DNS **A record** for the domain and for `www`, pointing to the server.
- [ ] Gmail: create a dedicated address and an App Password for the OTP emails.
      Gmail allows about 500 emails a day. Move to a mail service (Zoho, Amazon
      SES, Brevo) before you reach that.
- [ ] Google Cloud Console: add `https://your-domain` under **Authorized
      JavaScript origins** of your OAuth client, and publish the consent screen.

### Install

- [ ] On your PC: `.\tests\run-tests.ps1` must end with `0 failed`.
- [ ] On your PC: `.\deploy\make-release.ps1`, then copy the ZIP to the server.
- [ ] On the server: unzip into `/opt/medtrack`, then `sudo bash deploy/install.sh`.
- [ ] Fill in `.env` when asked, and run the script again.
- [ ] Open `https://your-domain`. The padlock must appear.
- [ ] `curl https://your-domain/api/health` answers `{"status":"ok"}`.

### First checks on the live site

- [ ] Sign up with the address in `ADMIN_EMAILS`. The OTP email must arrive.
- [ ] The **Admin** menu item appears.
- [ ] Sign in with Google.
- [ ] Add a medicine, make a bill, print it, send it on WhatsApp.
- [ ] Add a cashier, sign in as them, and confirm they cannot see Reports.
- [ ] On a phone: open the site and use **Add to Home Screen**.
- [ ] Make a second test shop and confirm it cannot see the first one's data.

### Backups

- [ ] Set up an off-server copy (`RCLONE_REMOTE`, see `deploy/backup-offsite.sh`).
      Without it, losing the server means losing every shop's data.
- [ ] Turn on your provider's automatic server snapshots.
- [ ] **Practise a restore** on a test copy (see DEPLOY.md). A backup that has never
      been restored is only a hope.

### Monitoring

- [ ] Add `https://your-domain/api/health` to a free uptime monitor (UptimeRobot,
      Better Stack) with alerts to your phone.

## Every week

- [ ] Check the Admin page: new shops, and whether they are really making bills.
- [ ] Check that backups are arriving off the server.

## Every update

- [ ] `.\tests\run-tests.ps1` must end with `0 failed`.
- [ ] `.\deploy\make-release.ps1`, copy to the server, unzip over the old files.
- [ ] `docker compose up -d --build`. Your data is in a Docker volume and is not touched.

## When you start charging

MedTrack launches free. When you are ready to charge:

- [ ] Decide your prices and set up a UPI ID or business account.
- [ ] Ask your CA about GST on your own subscription income.
- [ ] Follow "Starting to charge" in DEPLOY.md. Give at least 30 days' notice.

## When you grow

- More than about 300 active shops, or heavy reports slowing billing: move from
  SQLite to PostgreSQL and add a second server.
- Manual renewals taking too long: add a payment gateway such as Razorpay. It needs
  your business KYC and API keys.
