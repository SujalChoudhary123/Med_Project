#!/usr/bin/env bash
# Copies the database backups out of Docker, and off this server when a remote is set.
# install.sh runs this every night at 03:30.
#
# A backup on the same server is lost if the server is lost. To keep copies elsewhere:
#   1. curl https://rclone.org/install.sh | sudo bash
#   2. sudo rclone config        (connect Google Drive, Backblaze B2, S3...)
#   3. Add to .env:  RCLONE_REMOTE=mydrive:medtrack-backups

set -euo pipefail
cd "$(dirname "$0")/.."

local_dir=/var/backups/medtrack
mkdir -p "$local_dir"
chmod 700 "$local_dir"

docker compose cp api:/data/backups/. "$local_dir/"

# Keep 30 days here.
find "$local_dir" -name 'medtrack-*.db' -mtime +30 -delete

newest="$(ls -t "$local_dir"/medtrack-*.db | head -n 1)"
echo "$(date -Is) newest backup: $newest"

remote="$(grep -E '^RCLONE_REMOTE=' .env | cut -d= -f2- || true)"

if [ -n "$remote" ]; then
  rclone copy "$local_dir" "$remote" --max-age 3d
  echo "$(date -Is) copied to $remote"
else
  echo "$(date -Is) WARNING: RCLONE_REMOTE is not set, backups are only on this server"
fi
