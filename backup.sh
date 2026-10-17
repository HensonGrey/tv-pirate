#!/usr/bin/env bash
# Nightly copy of the database to Cloudflare R2, outside Oracle. Cron runs it (crontab -l); the bucket's
# 30-day lifecycle rule deletes old copies. A failure logs an ERROR to New Relic, which the error alert emails.
set -Eeuo pipefail
cd "$(dirname "$0")"
env_value() { grep -m1 "^$1=" .env | cut -d= -f2-; }

report() {
    curl -s -m 20 -X POST https://log-api.eu.newrelic.com/log/v1 \
        -H "Api-Key: $(env_value NEW_RELIC_LICENSE_KEY)" -H 'Content-Type: application/json' \
        -d "{\"service\":\"backup\",\"level\":\"$1\",\"message\":\"$2\"}" > /dev/null || true
}
trap 'report ERROR "database backup failed at line $LINENO of backup.sh"' ERR

name="tv-pirate-$(date -u +%F).sql.gz"
dump="$(mktemp)"
trap 'rm -f "$dump"' EXIT
# --no-owner: the copy restores into any database user, e.g. on a fresh server.
docker compose exec -T db sh -c 'pg_dump --no-owner --no-privileges -U "$POSTGRES_USER" tv-pirate' | gzip > "$dump"
docker run --rm -v "$dump:/backup.sql.gz:ro" \
    -e RCLONE_CONFIG_R2_TYPE=s3 -e RCLONE_CONFIG_R2_PROVIDER=Cloudflare -e RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true \
    -e RCLONE_CONFIG_R2_ACCESS_KEY_ID="$(env_value R2_ACCESS_KEY_ID)" \
    -e RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$(env_value R2_SECRET_ACCESS_KEY)" \
    -e RCLONE_CONFIG_R2_ENDPOINT="$(env_value R2_ENDPOINT)" \
    rclone/rclone:1.75 -q copyto /backup.sql.gz "r2:tv-pirate-backup/$name"
report INFO "database backup uploaded: $name ($(du -k "$dump" | cut -f1) KB)"
