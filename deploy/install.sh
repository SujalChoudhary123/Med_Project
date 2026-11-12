#!/usr/bin/env bash
# One-time server setup. Run as root from the project folder on Ubuntu 22.04 or 24.04:
#
#   cd /opt/medtrack && sudo bash deploy/install.sh
#
# Works on an ordinary VPS and on Oracle Cloud's free servers (ARM or AMD).
# Safe to run again. It never touches the database.

set -euo pipefail
cd "$(dirname "$0")/.."

if [ "$(id -u)" -ne 0 ]; then
  echo "Please run with sudo." >&2
  exit 1
fi

if [ ! -f .env ]; then
  cp .env.example .env
  chmod 600 .env
  echo "Created .env. Fill it in (nano .env), then run this script again."
  exit 1
fi

chmod 600 .env

# Reads one setting without running the file, so a value may contain spaces.
setting() {
  grep -E "^$1=" .env | tail -n 1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//' || true
}

for name in DOMAIN SMTP_EMAIL SMTP_APP_PASSWORD GOOGLE_CLIENT_ID ADMIN_EMAILS; do
  value="$(setting "$name")"
  case "$value" in
    ""|*example.com*|*xxxxxxxx*|your-*)
      echo "Please set $name in .env first." >&2
      exit 1
      ;;
  esac
done

domain="$(setting DOMAIN)"

# A very small server cannot build the app without some swap space.
memory_mb="$(awk '/MemTotal/ {print int($2 / 1024)}' /proc/meminfo)"
if [ "$memory_mb" -lt 2000 ] && [ ! -f /swapfile ]; then
  echo "Only ${memory_mb} MB of memory: adding 2 GB of swap..."
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# Firewall. Oracle's Ubuntu images use iptables, and Oracle warns against ufw on them.
if [ -f /etc/iptables/rules.v4 ] && grep -q InstanceServices /etc/iptables/rules.v4; then
  changed=false
  for port in 80 443; do
    if ! iptables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null; then
      iptables -I INPUT 1 -p tcp --dport "$port" -j ACCEPT
      changed=true
    fi
  done
  if [ "$changed" = true ]; then
    netfilter-persistent save
  fi
elif command -v ufw >/dev/null 2>&1; then
  ufw allow OpenSSH
  ufw allow 80/tcp
  ufw allow 443/tcp
  ufw allow 443/udp
  ufw --force enable
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Installing Docker..."
  curl -fsSL https://get.docker.com | sh
fi

echo "Building and starting MedTrack (the first time takes several minutes)..."
docker compose up -d --build

echo "Waiting for the API..."
status=""
for _ in $(seq 1 40); do
  status="$(docker compose ps --format '{{.Service}} {{.Health}}' 2>/dev/null | awk '$1=="api"{print $2}')"
  if [ "$status" = "healthy" ]; then
    break
  fi
  sleep 3
done

if [ "$status" != "healthy" ]; then
  echo "The API did not become healthy. See: docker compose logs api" >&2
  exit 1
fi

# Daily copy of the backups out of Docker. See deploy/backup-offsite.sh.
cron_file=/etc/cron.d/medtrack-backup
echo "30 3 * * * root cd $(pwd) && bash deploy/backup-offsite.sh >> /var/log/medtrack-backup.log 2>&1" > "$cron_file"
chmod 644 "$cron_file"

echo
echo "MedTrack is running: https://$domain"
echo "Health check:  curl https://$domain/api/health"
echo "Next: sign up with your admin address, then follow docs/LAUNCH_CHECKLIST.md"
