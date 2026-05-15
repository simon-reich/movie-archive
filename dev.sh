#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# dev.sh — MovieArchive local development starter
#
# Usage:  ./dev.sh
#
# 1. Starts Docker Compose (infra: Postgres, OpenSearch, Mailpit)
# 2. Waits until Postgres + OpenSearch are healthy
# 3. Opens a new Terminal tab → Spring Boot backend (./gradlew bootRun)
# 4. Opens a new Terminal tab → Nuxt frontend (pnpm dev)
# ─────────────────────────────────────────────────────────────────
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BACKEND="$ROOT/backend"
FRONTEND="$ROOT/frontend"

# ── Colours ──────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[dev]${NC} $*"; }
warn()  { echo -e "${YELLOW}[dev]${NC} $*"; }
error() { echo -e "${RED}[dev]${NC} $*" >&2; }

# ── Preflight checks ─────────────────────────────────────────────
if [[ ! -f "$ROOT/.env" ]]; then
  error ".env not found — copy .env.example and fill in the secrets."
  exit 1
fi

if ! command -v docker &>/dev/null; then
  error "docker is not installed or not in PATH."
  exit 1
fi

# ── 1. Start infrastructure ───────────────────────────────────────
info "Starting Docker Compose (infra only)…"
cd "$ROOT"
docker compose up -d

# ── 2. Wait for Postgres ──────────────────────────────────────────
info "Waiting for Postgres to be healthy…"
TIMEOUT=60
ELAPSED=0
until docker compose ps postgres --format '{{.Health}}' 2>/dev/null | grep -q "healthy"; do
  if [[ $ELAPSED -ge $TIMEOUT ]]; then
    error "Postgres did not become healthy within ${TIMEOUT}s."
    docker compose logs postgres --tail 20
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
info "Postgres is healthy. ✓"

# ── 3. Wait for OpenSearch ────────────────────────────────────────
info "Waiting for OpenSearch to be healthy…"
ELAPSED=0
until docker compose ps opensearch --format '{{.Health}}' 2>/dev/null | grep -q "healthy"; do
  if [[ $ELAPSED -ge $TIMEOUT ]]; then
    error "OpenSearch did not become healthy within ${TIMEOUT}s."
    docker compose logs opensearch --tail 20
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
info "OpenSearch is healthy. ✓"

# ── 4. Open Backend tab ───────────────────────────────────────────
info "Opening Backend tab (Spring Boot)…"
osascript <<EOF
tell application "Terminal"
    activate
    tell application "System Events"
        keystroke "t" using command down
    end tell
    delay 0.4
    do script "cd '$BACKEND' && echo '' && echo '── Spring Boot Backend ──────────────────────────────' && echo '' && ./gradlew bootRun" in selected tab of front window
end tell
EOF

# ── 5. Open Frontend tab ──────────────────────────────────────────
info "Opening Frontend tab (Nuxt)…"
osascript <<EOF
tell application "Terminal"
    activate
    tell application "System Events"
        keystroke "t" using command down
    end tell
    delay 0.4
    do script "cd '$FRONTEND' && echo '' && echo '── Nuxt Frontend ────────────────────────────────────' && echo '' && pnpm dev" in selected tab of front window
end tell
EOF

# ── Done ──────────────────────────────────────────────────────────
echo ""
info "All services started."
echo ""
echo "  Frontend  →  http://localhost:3000"
echo "  Backend   →  http://localhost:8080/actuator/health"
echo "  Mailpit   →  http://localhost:8025"
echo "  OpenSearch→  http://localhost:9200"
echo "  Dashboards→  http://localhost:5601"
echo ""
