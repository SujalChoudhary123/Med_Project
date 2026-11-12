# MedTrack ko free me live karna

Is guide se MedTrack bina server ka paisa diye internet par chalega:

| Cheez | Free option | Kharcha |
|---|---|---|
| Server | Oracle Cloud Always Free (Ampere A1: 2 CPU, 12 GB RAM) | ₹0 |
| Domain | DuckDNS, jaise `medtrack-shop.duckdns.org` | ₹0 |
| HTTPS | Caddy + Let's Encrypt (automatic) | ₹0 |
| OTP email | Tumhara Gmail App Password (pehle se set) | ₹0 |
| Backup | Google Drive ke 15 GB (rclone se) | ₹0 |

## Ye jaan lo pehle

- **Card lagega, charge nahi hoga.** Oracle signup par card verify karta hai aur chhota amount
  hold karke wapas kar deta hai. Oracle ka kehna hai ki upgrade kiye bina card charge nahi hota.
- **"Upgrade" kabhi mat dabana.** Free account me rahoge to bill nahi aayega.
- **Card kaunsa:** Visa ya Mastercard, credit card ya online/international enabled debit card.
  RuPay aksar reject hota hai. Virtual aur prepaid card nahi chalte. VPN band rakho.
- **Oracle free machine wapas le sakta hai** agar 7 din tak CPU, network aur memory sab 20% se
  kam rahe. Isliye backup Google Drive par zaroor rakho, aur uptime monitor lagao.
- **Oracle free ke rules badal sakta hai.** 2026 me hi usne free limit aadhi ki thi.
- Render jaise free hosts **mat lena**: wahan restart par SQLite database mit jata hai, yaani
  dukaan ka sara data chala jayega.

## Step 1: Oracle account

1. https://www.oracle.com/cloud/free/ par "Start for free" dabao.
2. **Home region** me Hyderabad ya Mumbai chuno. Baad me badal nahi sakte, aur free server sirf
   home region me banta hai.
3. Card verify karo.

## Step 2: Server banao

1. Console me **Compute > Instances > Create instance**.
2. **Image:** Canonical Ubuntu 24.04.
3. **Shape:** Ampere, `VM.Standard.A1.Flex`, 2 OCPU, 12 GB memory.
   - "Out of host capacity" aaye to doosra Availability Domain chuno, ya kuch ghante baad try karo.
   - Phir bhi na mile to `VM.Standard.E2.1.Micro` (1 GB RAM) lo. Chalega, par dheema hoga.
4. **SSH keys:** "Generate a key pair" chuno aur **private key download karo**. Isse
   `C:\Users\hp\.ssh\medtrack.key` me rakho. Ye file kisi ko mat dena, aur project folder me mat rakhna.
5. Create dabao. Banne ke baad **Public IP address** note karo.

## Step 3: Port 80 aur 443 kholo

1. Instance page se **Subnet > Security Lists > Default Security List** kholo.
2. **Add Ingress Rules**, do baar:
   - Source CIDR `0.0.0.0/0`, IP Protocol TCP, Destination Port `80`
   - Source CIDR `0.0.0.0/0`, IP Protocol TCP, Destination Port `443`

## Step 4: Free domain

1. https://www.duckdns.org par Google se login karo.
2. Ek naam daalo, jaise `medtrack-shop`.
3. **current ip** me server ka Public IP daalo aur **update ip** dabao.

Tumhara address ban gaya: `medtrack-shop.duckdns.org`

## Step 5: Google login ke liye

Google Cloud Console > APIs & Services > Credentials > tumhara OAuth client >
**Authorized JavaScript origins** me add karo: `https://medtrack-shop.duckdns.org`

## Step 6: Deploy

Ek hi command, apne PC se:

```powershell
.\deploy\deploy-from-pc.ps1 -Ip 1.2.3.4 -Domain medtrack-shop.duckdns.org -Key C:\Users\hp\.ssh\medtrack.key
```

Pehli baar 10-15 minute lag sakte hain. End me `MedTrack is live` aayega. Baad me update karna
ho to yahi command dobara chalao: data aur server ki settings safe rehti hain.

## Step 7: Live hone ke baad zaroor karo

1. `https://medtrack-shop.duckdns.org` kholo aur `ADMIN_EMAILS` wali email se signup karo.
2. **Admin** menu dikhna chahiye.
3. Ek test bill banao, aur phone par kholkar "Add to Home Screen" karo.
4. **Google Drive backup set karo** (`deploy/backup-offsite.sh` ke upar steps hain). Iske bina
   server gaya to sabka data gaya.
5. https://uptimerobot.com par free monitor lagao:
   `https://medtrack-shop.duckdns.org/api/health`

## Baad me

Jab paise aane lagen tab apna `.com` ya `.in` domain le lena. Sirf `.env` me `DOMAIN` badalna hai.
