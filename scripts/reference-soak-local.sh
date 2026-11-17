#!/usr/bin/env bash
set -euo pipefail

umask 077

readonly app_home="${EVO_SNT_HOME:-/opt/evo-snt}"
readonly config_file="${EVO_SNT_CONFIG:-/etc/evo-snt/reference-soak.json}"
readonly private_root="${EVO_SNT_EVIDENCE_ROOT:-/var/lib/evo-snt-reference-soak/private}"
readonly public_root="${EVO_SNT_PUBLIC_ROOT:-/var/lib/evo-snt-reference-soak/public}"
readonly request_timeout="${EVO_SNT_REQUEST_TIMEOUT:-30}"

if [[ -z "${EVO_SNT_URL:-}" ]]; then
  echo "EVO_SNT_URL is required" >&2
  exit 2
fi
if [[ ! -x "$app_home/bin/evo-snt" ]]; then
  echo "EVO.SNT CLI is not executable: $app_home/bin/evo-snt" >&2
  exit 2
fi
if [[ ! -r "$config_file" ]]; then
  echo "Soak configuration is not readable: $config_file" >&2
  exit 2
fi

token_file="${EVO_SNT_TOKEN_FILE:-}"
if [[ -z "$token_file" && -n "${CREDENTIALS_DIRECTORY:-}" ]]; then
  token_file="$CREDENTIALS_DIRECTORY/api-token"
fi
if [[ -z "$token_file" || ! -r "$token_file" ]]; then
  echo "Bearer token credential is not readable" >&2
  exit 2
fi

readonly started_at="$(date -u +%Y%m%dT%H%M%SZ)"
readonly run_prefix="${EVO_SNT_IDEMPOTENCY_PREFIX:-offline-soak-$started_at}"
readonly private_dir="$private_root/$started_at"
readonly public_dir="$public_root/$started_at"

install -d -m 0700 "$private_root" "$public_root" "$private_dir" "$public_dir"

global_args=(
  --url "$EVO_SNT_URL"
  --token-file "$token_file"
  --request-timeout "$request_timeout"
)
if [[ -n "${EVO_SNT_CA_FILE:-}" ]]; then
  global_args+=(--ca-file "$EVO_SNT_CA_FILE")
fi

exec "$app_home/bin/evo-snt" "${global_args[@]}" runs soak \
  --config "$config_file" \
  --idempotency-prefix "$run_prefix" \
  --output-dir "$private_dir" \
  --public-summary-dir "$public_dir"
