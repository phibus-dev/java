#!/usr/bin/env bash
set -Eeuo pipefail

VERSION="${VERSION:-2.4.0}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-ghcr.io/phibus-dev/s3-performance-test-web}"
IMAGE_DIGEST="${IMAGE_DIGEST:-}"
SOAK_SECONDS="${SOAK_SECONDS:-60}"
ARTIFACT_DIR="${ARTIFACT_DIR:-post-release-smoke-logs}"

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?$ ]]; then
  echo "Unsupported VERSION: $VERSION" >&2
  exit 2
fi
if ! [[ "$SOAK_SECONDS" =~ ^[0-9]+$ ]] || (( SOAK_SECONDS < 10 || SOAK_SECONDS > 3600 )); then
  echo "SOAK_SECONDS must be an integer between 10 and 3600" >&2
  exit 2
fi
if [[ -n "$IMAGE_DIGEST" && ! "$IMAGE_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "IMAGE_DIGEST must be empty or a sha256 digest" >&2
  exit 2
fi

SUFFIX="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
NETWORK="s3perf-smoke-${SUFFIX}"
POSTGRES_CONTAINER="s3perf-postgres-${SUFFIX}"
COORDINATOR_CONTAINER="s3perf-coordinator-${SUFFIX}"
AGENT_CONTAINER="s3perf-agent-${SUFFIX}"
COORDINATOR_VOLUME="s3perf-coordinator-config-${SUFFIX}"
AGENT_VOLUME="s3perf-agent-config-${SUFFIX}"
COORDINATOR_URL="http://127.0.0.1:18080"
POSTGRES_PASSWORD="s3perf-smoke-password"
BOOTSTRAP_KEY="post-release-smoke-bootstrap-key"
REGISTRATION_TOKEN="post-release-smoke-registration-token"
COOKIE_JAR="$ARTIFACT_DIR/cookies.txt"

if [[ -n "$IMAGE_DIGEST" ]]; then
  IMAGE_REF="${IMAGE_REPOSITORY}@${IMAGE_DIGEST}"
else
  IMAGE_REF="${IMAGE_REPOSITORY}:${VERSION}"
fi

mkdir -p "$ARTIFACT_DIR"

log() {
  printf '[post-release-smoke] %s\n' "$*"
}

capture_container() {
  local container="$1" label="$2"
  if docker inspect "$container" >/dev/null 2>&1; then
    docker logs "$container" >"$ARTIFACT_DIR/${label}.log" 2>&1 || true
    docker inspect "$container" >"$ARTIFACT_DIR/${label}-inspect.json" 2>&1 || true
  fi
}

cleanup() {
  local exit_code=$?
  set +e
  capture_container "$COORDINATOR_CONTAINER" coordinator-final
  capture_container "$AGENT_CONTAINER" agent-final
  capture_container "$POSTGRES_CONTAINER" postgres-final
  docker network inspect "$NETWORK" >"$ARTIFACT_DIR/network-final.json" 2>&1 || true
  docker rm -f "$AGENT_CONTAINER" "$COORDINATOR_CONTAINER" "$POSTGRES_CONTAINER" >/dev/null 2>&1 || true
  docker volume rm "$AGENT_VOLUME" "$COORDINATOR_VOLUME" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  exit "$exit_code"
}
trap cleanup EXIT

wait_for_postgres() {
  local deadline=$((SECONDS + 90))
  until docker exec "$POSTGRES_CONTAINER" pg_isready -U s3perf -d s3perf >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "PostgreSQL did not become ready" >&2
      return 1
    fi
    sleep 2
  done
}

wait_for_session() {
  local deadline=$((SECONDS + 120))
  until curl -fsS "$COORDINATOR_URL/api/session" >/dev/null; do
    if (( SECONDS >= deadline )); then
      echo "Coordinator did not expose /api/session" >&2
      return 1
    fi
    sleep 2
  done
}

wait_for_readiness() {
  local deadline=$((SECONDS + 120))
  until curl -fsS "$COORDINATOR_URL/actuator/health/readiness" | jq -e '.status == "UP"' >/dev/null; do
    if (( SECONDS >= deadline )); then
      echo "Coordinator readiness did not become UP" >&2
      return 1
    fi
    sleep 2
  done
}

agent_is_online() {
  curl -fsS "$COORDINATOR_URL/api/agents" \
    | jq -e --arg version "$VERSION" \
      'any(.[]; .name == "release-smoke-agent" and .version == $version and .status == "ONLINE")' \
      >/dev/null
}

wait_for_agent() {
  local deadline=$((SECONDS + 120))
  until agent_is_online; do
    if (( SECONDS >= deadline )); then
      echo "Agent did not register as ONLINE with version $VERSION" >&2
      return 1
    fi
    sleep 2
  done
}

start_coordinator() {
  docker run -d --name "$COORDINATOR_CONTAINER" \
    --network "$NETWORK" --network-alias coordinator \
    -p 127.0.0.1:18080:8080 \
    -e S3_PERF_BOOTSTRAP_KEY="$BOOTSTRAP_KEY" \
    -e S3_PERF_BOOTSTRAP_FILE=/app/config/bootstrap-settings.json \
    -e S3PERF_APPLICATION_MODE=COORDINATOR \
    -e S3PERF_DISTRIBUTED_REGISTRATION_TOKEN="$REGISTRATION_TOKEN" \
    -e S3PERF_RUNTIME_MAX_CONCURRENT_RUNS=2 \
    -e S3PERF_RUNTIME_MAX_WORKER_TASKS=4 \
    -e S3PERF_POSTGRESQL_POOL_MAXIMUM_SIZE=4 \
    -e MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always \
    -v "$COORDINATOR_VOLUME:/app/config" \
    "$IMAGE_REF" >/dev/null
}

start_agent() {
  docker run -d --name "$AGENT_CONTAINER" \
    --network "$NETWORK" \
    -e S3_PERF_BOOTSTRAP_KEY="$BOOTSTRAP_KEY" \
    -e S3_PERF_BOOTSTRAP_FILE=/app/config/bootstrap-settings.json \
    -e S3PERF_APPLICATION_MODE=AGENT \
    -e S3PERF_AGENT_COORDINATOR_URL=http://coordinator:8080 \
    -e S3PERF_AGENT_REGISTRATION_TOKEN="$REGISTRATION_TOKEN" \
    -e S3PERF_AGENT_NAME=release-smoke-agent \
    -e S3PERF_AGENT_IDENTITY_FILE=/app/config/agent-identity.json \
    -e S3PERF_AGENT_HEARTBEAT_INTERVAL_MS=2000 \
    -e S3PERF_AGENT_POLL_INTERVAL_MS=1000 \
    -e S3PERF_AGENT_STATISTICS_INTERVAL_MS=1000 \
    -e S3PERF_RUNTIME_MAX_CONCURRENT_RUNS=1 \
    -e S3PERF_RUNTIME_MAX_WORKER_TASKS=2 \
    -v "$AGENT_VOLUME:/app/config" \
    "$IMAGE_REF" >/dev/null
}

log "Pulling released image $IMAGE_REF"
docker pull "$IMAGE_REF" >/dev/null
RESOLVED_IMAGE="$(docker image inspect "$IMAGE_REF" --format '{{index .RepoDigests 0}}')"
if [[ -n "$IMAGE_DIGEST" && "$RESOLVED_IMAGE" != *"@${IMAGE_DIGEST}" ]]; then
  echo "Resolved image $RESOLVED_IMAGE does not match $IMAGE_DIGEST" >&2
  exit 1
fi
printf '%s\n' "$RESOLVED_IMAGE" >"$ARTIFACT_DIR/resolved-image.txt"

docker network create "$NETWORK" >/dev/null
docker volume create "$COORDINATOR_VOLUME" >/dev/null
docker volume create "$AGENT_VOLUME" >/dev/null

log "Starting PostgreSQL 16"
docker run -d --name "$POSTGRES_CONTAINER" \
  --network "$NETWORK" --network-alias postgres \
  -e POSTGRES_DB=s3perf \
  -e POSTGRES_USER=s3perf \
  -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  postgres:16-alpine >/dev/null
wait_for_postgres

log "Starting coordinator in bootstrap mode"
start_coordinator
wait_for_session

SESSION="$(curl -fsS -c "$COOKIE_JAR" "$COORDINATOR_URL/api/session")"
printf '%s\n' "$SESSION" >"$ARTIFACT_DIR/session-bootstrap.json"
CSRF_TOKEN="$(jq -er '.csrfToken' <<<"$SESSION")"
CSRF_HEADER="$(jq -er '.csrfHeaderName' <<<"$SESSION")"

SETTINGS_PAYLOAD="$(jq -nc \
  --arg jdbcUrl 'jdbc:postgresql://postgres:5432/s3perf' \
  --arg username 's3perf' \
  --arg password "$POSTGRES_PASSWORD" \
  '{jdbcUrl:$jdbcUrl,postgresUsername:$username,postgresPassword:$password,
    vaultAddress:"",vaultAuthMethod:"TOKEN",vaultToken:"",vaultAuthMount:"approle",
    vaultRoleId:"",vaultSecretId:"",vaultKvMount:"secret",vaultSecretPrefix:"s3-performance",
    vaultTlsVerify:true,vaultCaCertificatePath:"",s3ProfileName:"default",s3Endpoint:"",
    s3Region:"us-east-1",s3Bucket:"",s3PathStyleAccess:true,s3CredentialsSource:"VAULT",
    s3VaultSecretPath:"",s3AccessKeyField:"accessKey",s3SecretKeyField:"secretKey",
    s3AccessKey:"",s3SecretKey:""}')"

log "Saving PostgreSQL bootstrap settings"
curl -fsS -b "$COOKIE_JAR" \
  -H "$CSRF_HEADER: $CSRF_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "$SETTINGS_PAYLOAD" \
  "$COORDINATOR_URL/api/settings" \
  | tee "$ARTIFACT_DIR/settings-save.json" \
  | jq -e '.saved == true' >/dev/null
docker exec "$COORDINATOR_CONTAINER" test -s /app/config/bootstrap-settings.json

log "Restarting coordinator to apply Flyway migrations"
capture_container "$COORDINATOR_CONTAINER" coordinator-bootstrap
docker rm -f "$COORDINATOR_CONTAINER" >/dev/null
start_coordinator
wait_for_readiness

SESSION="$(curl -fsS "$COORDINATOR_URL/api/session")"
printf '%s\n' "$SESSION" >"$ARTIFACT_DIR/session-ready.json"
jq -e --arg version "$VERSION" '.version == $version' <<<"$SESSION" >/dev/null
FIRST_STARTED_AT="$(jq -er '.startedAt' <<<"$SESSION")"

curl -fsS "$COORDINATOR_URL/actuator/health" \
  | tee "$ARTIFACT_DIR/health-ready.json" \
  | jq -e '.status == "UP" and .components.externalServices.status == "UP"' >/dev/null
curl -fsS "$COORDINATOR_URL/actuator/prometheus" >"$ARTIFACT_DIR/prometheus-before-restart.txt"
grep -q '^s3perf_runtime_' "$ARTIFACT_DIR/prometheus-before-restart.txt"
grep -q '^hikaricp_' "$ARTIFACT_DIR/prometheus-before-restart.txt"

APPLIED_MIGRATIONS="$(docker exec "$POSTGRES_CONTAINER" psql -U s3perf -d s3perf -Atc \
  "SELECT count(*) FROM flyway_schema_history WHERE success = TRUE")"
LATEST_MIGRATION="$(docker exec "$POSTGRES_CONTAINER" psql -U s3perf -d s3perf -Atc \
  "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1")"
if (( APPLIED_MIGRATIONS < 24 )) || [[ "$LATEST_MIGRATION" != "24" ]]; then
  echo "Expected at least 24 successful migrations through V24; got $APPLIED_MIGRATIONS through V$LATEST_MIGRATION" >&2
  exit 1
fi

EXPECTED_TABLES="$(docker exec "$POSTGRES_CONTAINER" psql -U s3perf -d s3perf -Atc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN
   ('test_run','test_schedule','security_audit_event','s3_profile','clickhouse_profile',
    'clickhouse_test_run','clickhouse_replication_snapshot','clickhouse_replicated_scenario_run',
    'clickhouse_failover_run')")"
[[ "$EXPECTED_TABLES" == "9" ]]

log "Verifying X-Agent-Token protocol"
PROBE_PAYLOAD="$(jq -nc --arg version "$VERSION" \
  '{name:"token-probe",hostname:"token-probe",address:"",version:$version,cpuCount:1,
    memoryBytes:1048576,tags:{capabilities:"S3"}}')"
PROBE_REGISTRATION="$(curl -fsS -X POST \
  -H "X-Agent-Registration-Token: $REGISTRATION_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "$PROBE_PAYLOAD" \
  "$COORDINATOR_URL/api/agents/register")"
PROBE_ID="$(jq -er '.agentId' <<<"$PROBE_REGISTRATION")"
PROBE_TOKEN="$(jq -er '.agentToken' <<<"$PROBE_REGISTRATION")"
HEARTBEAT_PAYLOAD="$(jq -nc --arg version "$VERSION" \
  '{version:$version,cpuCount:1,memoryBytes:1048576,tags:{capabilities:"S3"}}')"
GOOD_CODE="$(curl -sS -o "$ARTIFACT_DIR/token-heartbeat-good.json" -w '%{http_code}' -X POST \
  -H "X-Agent-Token: $PROBE_TOKEN" -H 'Content-Type: application/json' \
  -d "$HEARTBEAT_PAYLOAD" "$COORDINATOR_URL/api/agents/$PROBE_ID/heartbeat")"
BAD_CODE="$(curl -sS -o "$ARTIFACT_DIR/token-heartbeat-bad.json" -w '%{http_code}' -X POST \
  -H 'X-Agent-Token: invalid' -H 'Content-Type: application/json' \
  -d "$HEARTBEAT_PAYLOAD" "$COORDINATOR_URL/api/agents/$PROBE_ID/heartbeat")"
[[ "$GOOD_CODE" == "200" ]]
[[ "$BAD_CODE" == "401" ]]

log "Starting released agent"
start_agent
wait_for_agent
AGENT_ID="$(curl -fsS "$COORDINATOR_URL/api/agents" \
  | jq -er '.[] | select(.name == "release-smoke-agent") | .id')"

log "Restarting coordinator and waiting for agent re-registration"
capture_container "$COORDINATOR_CONTAINER" coordinator-before-restart
docker rm -f "$COORDINATOR_CONTAINER" >/dev/null
start_coordinator
wait_for_readiness
wait_for_agent
AGENT_ID_AFTER_COORDINATOR_RESTART="$(curl -fsS "$COORDINATOR_URL/api/agents" \
  | jq -er '.[] | select(.name == "release-smoke-agent") | .id')"
[[ "$AGENT_ID_AFTER_COORDINATOR_RESTART" != "$AGENT_ID" ]]
SESSION="$(curl -fsS "$COORDINATOR_URL/api/session")"
SECOND_STARTED_AT="$(jq -er '.startedAt' <<<"$SESSION")"
[[ "$FIRST_STARTED_AT" != "$SECOND_STARTED_AT" ]]

MIGRATIONS_AFTER_RESTART="$(docker exec "$POSTGRES_CONTAINER" psql -U s3perf -d s3perf -Atc \
  "SELECT count(*) FROM flyway_schema_history WHERE success = TRUE")"
[[ "$MIGRATIONS_AFTER_RESTART" == "$APPLIED_MIGRATIONS" ]]

log "Restarting agent with its persisted identity"
capture_container "$AGENT_CONTAINER" agent-before-restart
docker rm -f "$AGENT_CONTAINER" >/dev/null
start_agent
wait_for_agent
AGENT_ID_AFTER_RESTART="$(curl -fsS "$COORDINATOR_URL/api/agents" \
  | jq -er '.[] | select(.name == "release-smoke-agent") | .id')"
[[ "$AGENT_ID_AFTER_RESTART" == "$AGENT_ID_AFTER_COORDINATOR_RESTART" ]]

log "Running ${SOAK_SECONDS}s readiness and heartbeat soak"
SOAK_DEADLINE=$((SECONDS + SOAK_SECONDS))
SOAK_SAMPLES=0
while (( SECONDS < SOAK_DEADLINE )); do
  curl -fsS "$COORDINATOR_URL/actuator/health/readiness" | jq -e '.status == "UP"' >/dev/null
  agent_is_online
  curl -fsS "$COORDINATOR_URL/actuator/prometheus" >/dev/null
  SOAK_SAMPLES=$((SOAK_SAMPLES + 1))
  sleep 5
done

if docker logs "$COORDINATOR_CONTAINER" 2>&1 | grep -Eq 'APPLICATION FAILED TO START|OutOfMemoryError'; then
  echo "Fatal marker found in coordinator log" >&2
  exit 1
fi
if docker logs "$AGENT_CONTAINER" 2>&1 | grep -Eq 'APPLICATION FAILED TO START|OutOfMemoryError'; then
  echo "Fatal marker found in agent log" >&2
  exit 1
fi

cat >"$ARTIFACT_DIR/summary.md" <<EOF
# Post-release smoke $VERSION

- Image: \`$RESOLVED_IMAGE\`
- Flyway: $APPLIED_MIGRATIONS successful migrations, latest V$LATEST_MIGRATION
- Coordinator version: $VERSION
- Coordinator restart: passed
- Agent registration and persisted identity restart: passed
- X-Agent-Token valid/invalid checks: HTTP $GOOD_CODE / HTTP $BAD_CODE
- Runtime and HikariCP metrics: present
- Soak: ${SOAK_SECONDS}s, $SOAK_SAMPLES samples
EOF

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  cat "$ARTIFACT_DIR/summary.md" >>"$GITHUB_STEP_SUMMARY"
fi

log "Post-release smoke passed for $RESOLVED_IMAGE"
