# MedTrack: pharmacy management with GST billing

A web app for Indian pharmacies. Each shop signs up and gets its own private data. It
launches free, and paid plans can be switched on later with one setting (`PAID_START`,
see DEPLOY.md). Built with a Java API (SQLite) and a React front end.

## What it does

- **Inventory** with batch, expiry, stock, GST rate, HSN and cost price. Bulk upload by CSV.
- **Alerts** for low stock and expiring or expired medicines.
- **Multi-item GST billing**: search or scan, discounts per line, CGST + SGST, automatic
  invoice numbers, printable A4 tax invoice, send on WhatsApp, cancel a bill.
- **Returns and credit notes**, with or without putting the stock back.
- **Customers and udhaar**: accounts, part payments, credit limits, statements,
  WhatsApp reminders.
- **Purchases and suppliers**: stock in per batch, free units, average cost, supplier
  accounts and payments, duplicate-bill protection.
- **Reports**: sales, GST by rate, HSN summary, input GST, profit, and CSV files for your
  accountant.
- **Staff accounts** with roles: owner, pharmacist, cashier.
- **Activity log** of who did what, and a **download of all the shop's data**.
- **Sign-in** by password, email OTP, or Google.
- **Works on phones**, installs to the home screen, and opens without internet. A bill is
  kept on screen when the connection drops and can never be saved twice.
- **Business side**: landing page, free trial, plans, admin page, Terms and Privacy.
- **Automatic database backups**.

Roles: the **owner** can do everything. A **pharmacist** manages medicines, purchases,
bills, returns and reports. A **cashier** can bill, handle customers and look up stock.
Cost prices are never shown to cashiers.

## Run it on your PC

1. Copy `server.env.example` to `server.env` and fill in your Gmail App Password and
   Google client ID (once).
2. Start the API (compiles and runs it): `.\run-local.ps1`
3. In a second terminal: `cd frontend`, then `npm install` (first time), then `npm run dev`
4. Open http://localhost:5173

## Use it on a phone or tablet

Once deployed, open your domain on the phone and choose **Add to Home Screen**. To try it
before deploying, with the phone and PC on the same WiFi:

1. Start the API as usual (`.\run-local.ps1`).
2. In `frontend`, run `npm run dev:phone`.
3. On the phone open `http://<your PC's address>:5173` (Vite prints it as "Network").
4. Sign in with username and password. Google sign-in does not work on a plain IP
   address, and the home-screen install needs HTTPS. Both work on your real domain.

## Check that everything works

```powershell
.\tests\run-tests.ps1
```

It builds the server, starts it on a throw-away database, and runs about 200 checks: GST
maths, sign-in, forged Google tokens, roles, billing, udhaar, purchases, profit, plans,
shop isolation, many people at once, and a backup restore drill. It never touches your
real data. It must end with `0 failed`.

## Put it on the internet

- [docs/FREE_DEPLOY.md](docs/FREE_DEPLOY.md): going live for free (Oracle server, DuckDNS name).
- [DEPLOY.md](DEPLOY.md): the install itself.
- [docs/LAUNCH_CHECKLIST.md](docs/LAUNCH_CHECKLIST.md): everything to do before the first
  customer, including what needs a CA and a lawyer.
- [docs/USER_GUIDE.md](docs/USER_GUIDE.md): guide for shop owners, in English and Hindi.

## Folders

| Path | Contents |
|---|---|
| `src/` | Java API: `ApiServer` (routes), `DatabaseHelper` (database), `Billing`, `Returns`, `Customers`, `Purchases`, `ReportService`, `Plans`, `Audit`, `Export`, `AuthService`, `GoogleAuth`, `SessionManager`, `Backup`, `Config` |
| `frontend/` | React app. `public/` holds the Terms, Privacy, app icons and service worker |
| `tests/` | Automatic tests (`run-tests.ps1`) |
| `deploy/`, `Dockerfile.*`, `docker-compose.yml` | Server deployment |
| `docs/` | Guides. `docs/archive/` holds old notes, out of date |
