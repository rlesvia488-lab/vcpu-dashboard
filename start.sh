#!/bin/bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export VAULT_URI="${VAULT_URI:?Set VAULT_URI}"
export VAULT_ROLE_ID="${VAULT_ROLE_ID:?Set VAULT_ROLE_ID}"
export VAULT_SECRET_ID="${VAULT_SECRET_ID:?Set VAULT_SECRET_ID}"
export VAULT_CONTEXT="${VAULT_CONTEXT:-cloud/capacity/default}"
export VAULT_TECH_ACCOUNTS_PATH="${VAULT_TECH_ACCOUNTS_PATH:-${VAULT_CONTEXT}}"
export VAULT_NAMESPACE="${VAULT_NAMESPACE:-}"
export VCPU_DASHBOARD_PORT="${VCPU_DASHBOARD_PORT:-8080}"
export VCPU_DASHBOARD_LOG="${VCPU_DASHBOARD_LOG:-${APP_DIR}/vcpu-dashboard.log}"
export REFRESH_INTERVAL_SECONDS="${REFRESH_INTERVAL_SECONDS:-300}"
export TRACE_OCS_RESPONSE_BODIES="${TRACE_OCS_RESPONSE_BODIES:-false}"
exec java -server -Xms128m -Xmx384m \
  -Dspring.cloud.vault.uri="${VAULT_URI}" \
  -Dspring.cloud.vault.app-role.role-id="${VAULT_ROLE_ID}" \
  -Dspring.cloud.vault.app-role.secret-id="${VAULT_SECRET_ID}" \
  -Dspring.cloud.vault.kv.default-context="${VAULT_CONTEXT}" \
  -Dspring.cloud.vault.namespace="${VAULT_NAMESPACE}" \
  -Dvcpu.dashboard.vault.path="${VAULT_TECH_ACCOUNTS_PATH}" \
  -jar "${APP_DIR}/vcpu-dashboard.jar"
