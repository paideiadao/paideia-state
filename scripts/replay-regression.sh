#!/usr/bin/env bash
# replay-regression.sh — behavior-preservation regression harness.
#
# Runs a replica of paideia-state against a fresh copy of prod's replay
# state (transaction_archive/daoconfigs/stakingStates/proposals/errors),
# lets it sync to tip talking to the real Ergo node ONLY through a proxy
# that blocks transaction broadcast, then diffs its API responses against
# the live prod state service (the reference implementation).
#
# See README.md "## Replay regression test" for full documentation.
set -euo pipefail

# ----------------------------------------------------------------------------
# Defaults / flags
# ----------------------------------------------------------------------------
NO_BUILD=0
KEEP=0
TIMEOUT_MIN=45
DATA_DIR="/home/luivatra/develop/paideia/.replay-test"
RESTART=0
RESTART_TIMEOUT_SECS=600
MULTI_OPERATOR=0

usage() {
  cat <<'EOF'
Usage: replay-regression.sh [--no-build] [--keep] [--timeout MIN] [--data DIR]
                             [--restart] [--restart-timeout SEC]
                             [--multi-operator [N]]

  --no-build             Skip `docker compose build` (use whatever image is local)
  --keep                 Keep the run dir and container logs even on success
  --timeout MIN          Minutes to wait for the replica to sync (default: 45)
  --data DIR             Source dir holding transaction_archive/ etc.
                          (default: /home/luivatra/develop/paideia/.replay-test)
  --restart              After the normal diff passes, `docker restart` the replica,
                          wait for /ready, assert it resumed from a persisted
                          checkpoint rather than falling back to a full replay, and
                          re-run the diff phase against the restarted replica.
                          Not supported together with --multi-operator.
  --restart-timeout SEC  Seconds to wait for the replica to become ready again after
                          --restart (default: 600)
  --multi-operator [N]   Run N replicas (N is 2 or 3, default 3) against fresh, fully
                          independent copies of the replay state, each running with a
                          different OPERATOR_ADDRESS/UI_FEE_ADDRESS, and additionally
                          assert that every replica converges to identical API state
                          (not just replica-1 vs prod). Cannot be combined with
                          --restart.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build) NO_BUILD=1; shift ;;
    --keep) KEEP=1; shift ;;
    --timeout) TIMEOUT_MIN="$2"; shift 2 ;;
    --data) DATA_DIR="$2"; shift 2 ;;
    --restart) RESTART=1; shift ;;
    --restart-timeout) RESTART_TIMEOUT_SECS="$2"; shift 2 ;;
    --multi-operator)
      MULTI_OPERATOR=3
      if [[ $# -ge 2 && "${2}" =~ ^[0-9]+$ ]]; then
        MULTI_OPERATOR="$2"
        shift
      fi
      shift
      ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

if [[ "${MULTI_OPERATOR}" -ne 0 && "${MULTI_OPERATOR}" -ne 2 && "${MULTI_OPERATOR}" -ne 3 ]]; then
  echo "ERROR: --multi-operator N must be 2 or 3 (got '${MULTI_OPERATOR}')" >&2
  exit 1
fi

if [[ "${RESTART}" -eq 1 && "${MULTI_OPERATOR}" -ne 0 ]]; then
  echo "ERROR: --restart cannot be combined with --multi-operator (unsupported combination)" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

PROD_NODE="http://192.168.1.137:9053"
PROD_STATE="http://192.168.1.137:9124"

RUN_ID="$$"
NETWORK="replay-net-${RUN_ID}"
PROXY_NAME="replay-txblock-${RUN_ID}"
REPLICA_NAME="replay-replica-${RUN_ID}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_DIR="${STATE_DIR}/.replay-run/${TS}"

# Container names for every replica in this run. Single mode (the default):
# just REPLICA_NAME, unchanged from before --multi-operator existed. Multi
# mode: one name per replica, "replay-replica-${RUN_ID}-${i}".
if [[ "${MULTI_OPERATOR}" -eq 0 ]]; then
  REPLICA_NAMES=("${REPLICA_NAME}")
else
  REPLICA_NAMES=()
  i=1
  while [[ "${i}" -le "${MULTI_OPERATOR}" ]]; do
    REPLICA_NAMES+=("replay-replica-${RUN_ID}-${i}")
    i=$(( i + 1 ))
  done
fi

DATA_DIRS=(transaction_archive errors stakingStates daoconfigs proposals state)

# OPERATOR_ADDRESS/UI_FEE_ADDRESS per replica in --multi-operator mode. Index 0
# (replica 1) is the address single-replica mode has always used, so replica 1
# in multi mode is identical to today's lone replica in everything but name/port.
OPERATOR_ADDRESSES=(
  "9h7L7sUHZk43VQC3PHtSp5ujAWcZtYmWATBH746wi75C5XHi68b"
  "9fSgJ7BmUxBQJ454prQDQ7fQMBkXPLaAmDnimgTtjym6FYPHjAV"
  "9g2GcDzTD4byrbsV4YA24ZjPrwYeg6UbY46KbQSQFAa4YCo887G"
)

FAILED=0
CLEANED=0

log() { echo "[replay-regression] $*"; }

# ----------------------------------------------------------------------------
# Cleanup (always runs once, via trap)
# ----------------------------------------------------------------------------
cleanup() {
  if [[ "${CLEANED}" -eq 1 ]]; then
    return
  fi
  CLEANED=1

  mkdir -p "${RUN_DIR}/logs" 2>/dev/null || true

  if [[ "${MULTI_OPERATOR}" -eq 0 ]]; then
    docker logs "${REPLICA_NAME}" >"${RUN_DIR}/logs/replica.log" 2>&1 || true
    docker rm -f "${REPLICA_NAME}" >/dev/null 2>&1 || true
  else
    local i=1 name
    for name in "${REPLICA_NAMES[@]}"; do
      docker logs "${name}" >"${RUN_DIR}/logs/replica-${i}.log" 2>&1 || true
      docker rm -f "${name}" >/dev/null 2>&1 || true
      i=$(( i + 1 ))
    done
  fi
  docker logs "${PROXY_NAME}" >"${RUN_DIR}/logs/proxy.log" 2>&1 || true

  docker rm -f "${PROXY_NAME}" >/dev/null 2>&1 || true
  docker network rm "${NETWORK}" >/dev/null 2>&1 || true

  if [[ "${FAILED}" -eq 0 && "${KEEP}" -eq 0 ]]; then
    rm -rf "${RUN_DIR}"
  else
    log "Run directory preserved: ${RUN_DIR}"
  fi
}
trap cleanup EXIT

# ----------------------------------------------------------------------------
# Helpers used in the diff phase
# ----------------------------------------------------------------------------
sanitize_endpoint() {
  # "/dao/<key>/config" -> "dao_<key>_config"
  local ep="${1#/}"
  printf '%s' "${ep//\//_}"
}

fetch_and_normalize() {
  # $1 = url, $2 = output file, $3 = endpoint path. Pretty-prints JSON (sorted
  # keys) if the body parses as JSON, otherwise saves the raw body.
  # /health is a liveness check whose body carries instance-specific sync
  # status (heights, lag), so only its "status" field is compared.
  local url="$1" outfile="$2" ep="${3:-}" body filter
  body="$(curl -s -m 30 "${url}" || true)"
  if [[ "${ep}" == "/health" ]]; then
    filter='import json,sys; d=json.load(sys.stdin); print(json.dumps({"status": d.get("status")}, sort_keys=True, indent=1))'
  else
    filter='import json,sys; print(json.dumps(json.load(sys.stdin), sort_keys=True, indent=1))'
  fi
  if ! printf '%s' "${body}" | python3 -c "${filter}" >"${outfile}" 2>/dev/null; then
    printf '%s' "${body}" >"${outfile}"
  fi
}

copy_data_dir_set() {
  # $1 = destination dir to populate with a fresh copy of DATA_DIRS from
  # DATA_DIR. Same logic as the original single-replica copy loop, just
  # parameterized so --multi-operator can call it once per replica and give
  # each one its own writable dirs.
  local dest="$1" d
  mkdir -p "${dest}"
  for d in "${DATA_DIRS[@]}"; do
    if [[ -d "${DATA_DIR}/${d}" ]]; then
      cp -a "${DATA_DIR}/${d}" "${dest}/${d}"
    else
      mkdir -p "${dest}/${d}"
    fi
  done
}

start_replica() {
  # $1 = container name, $2 = host port, $3 = mount base dir (holds the
  # DATA_DIRS copies to bind-mount), $4 = operator/UI-fee address.
  local name="$1" port="$2" mount_base="$3" operator="$4" secret
  secret="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"

  log "Starting replica container ${name}"
  docker run -d \
    --name "${name}" \
    --network "${NETWORK}" \
    -p "${port}:9000" \
    -v "${mount_base}/transaction_archive:/opt/docker/transaction_archive" \
    -v "${mount_base}/errors:/opt/docker/errors" \
    -v "${mount_base}/stakingStates:/opt/docker/stakingStates" \
    -v "${mount_base}/daoconfigs:/opt/docker/daoconfigs" \
    -v "${mount_base}/proposals:/opt/docker/proposals" \
    -v "${mount_base}/state:/opt/docker/state" \
    -e "ERGO_NODE=http://${PROXY_NAME}:9053" \
    -e "OPERATOR_ADDRESS=${operator}" \
    -e "UI_FEE_ADDRESS=${operator}" \
    -e "ZMQ_HOST=${PROXY_NAME}" \
    -e "ZMQ_PORT=9999" \
    -e "APPLICATION_SECRET=${secret}" \
    ghcr.io/paideiadao/paideia-state:latest >/dev/null
}

# ----------------------------------------------------------------------------
# 1. Preflight
# ----------------------------------------------------------------------------
log "Preflight checks..."

command -v docker >/dev/null 2>&1 || {
  echo "ERROR: docker not found in PATH" >&2
  exit 1
}

if [[ ! -d "${DATA_DIR}/transaction_archive" ]]; then
  echo "ERROR: --data dir '${DATA_DIR}' has no transaction_archive/ subdirectory" >&2
  exit 1
fi

if ! curl -sf -o /dev/null "${PROD_STATE}/health"; then
  echo "ERROR: prod state service (${PROD_STATE}/health) is not reachable / not 200" >&2
  exit 1
fi

if ! curl -sf -o /dev/null "${PROD_STATE}/dao"; then
  echo "ERROR: prod state service (${PROD_STATE}/dao) is not reachable / not 200" >&2
  exit 1
fi

if ! curl -sf -o /dev/null "${PROD_NODE}/info"; then
  echo "ERROR: prod Ergo node (${PROD_NODE}/info) is not reachable" >&2
  exit 1
fi

log "Preflight OK."

# ----------------------------------------------------------------------------
# 2. Build
# ----------------------------------------------------------------------------
if [[ "${NO_BUILD}" -eq 0 ]]; then
  log "Building image (docker compose build)..."
  (cd "${STATE_DIR}" && docker compose build)
else
  log "Skipping build (--no-build)."
fi

# ----------------------------------------------------------------------------
# 3. Run dir: fresh copy of the replay state
# ----------------------------------------------------------------------------
log "Preparing run dir: ${RUN_DIR}"
mkdir -p "${RUN_DIR}"
if [[ "${MULTI_OPERATOR}" -eq 0 ]]; then
  copy_data_dir_set "${RUN_DIR}"
  mkdir -p "${RUN_DIR}/logs" "${RUN_DIR}/responses/replica" "${RUN_DIR}/responses/prod"
else
  # Each replica gets its own fresh copy under replica-${i}/ so they never
  # share writable dirs — that's the whole point of the determinism check.
  mkdir -p "${RUN_DIR}/logs" "${RUN_DIR}/responses/prod"
  i=1
  while [[ "${i}" -le "${MULTI_OPERATOR}" ]]; do
    copy_data_dir_set "${RUN_DIR}/replica-${i}"
    mkdir -p "${RUN_DIR}/responses/replica-${i}"
    i=$(( i + 1 ))
  done
fi

# ----------------------------------------------------------------------------
# 4. Network
# ----------------------------------------------------------------------------
log "Creating docker network ${NETWORK}"
docker network create "${NETWORK}" >/dev/null

# ----------------------------------------------------------------------------
# 5. Tx-block proxy container
# ----------------------------------------------------------------------------
log "Starting tx-block proxy container ${PROXY_NAME}"
docker run -d \
  --name "${PROXY_NAME}" \
  --network "${NETWORK}" \
  -v "${SCRIPT_DIR}/txblock-proxy.py:/txblock-proxy.py:ro" \
  -e "UPSTREAM=${PROD_NODE}" \
  python:3.11-alpine \
  python /txblock-proxy.py >/dev/null

# ----------------------------------------------------------------------------
# 6. Replica container(s)
# ----------------------------------------------------------------------------
if [[ "${MULTI_OPERATOR}" -eq 0 ]]; then
  start_replica "${REPLICA_NAME}" 9125 "${RUN_DIR}" "${OPERATOR_ADDRESSES[0]}"
else
  i=1
  while [[ "${i}" -le "${MULTI_OPERATOR}" ]]; do
    start_replica "${REPLICA_NAMES[$((i-1))]}" "$((9124 + i))" "${RUN_DIR}/replica-${i}" "${OPERATOR_ADDRESSES[$((i-1))]}"
    i=$(( i + 1 ))
  done
fi

# ----------------------------------------------------------------------------
# 7. Wait for sync
# ----------------------------------------------------------------------------
if [[ "${MULTI_OPERATOR}" -eq 0 ]]; then
  log "Waiting for replica to sync (timeout ${TIMEOUT_MIN}m)..."
  TIMEOUT_SECS=$(( TIMEOUT_MIN * 60 ))
  START_TS=$(date +%s)
  LAST_PROGRESS_TS=${START_TS}
  ELAPSED=0

  while true; do
    NOW_TS=$(date +%s)
    ELAPSED=$(( NOW_TS - START_TS ))

    if [[ "$(docker inspect -f '{{.State.Running}}' "${REPLICA_NAME}" 2>/dev/null || echo false)" != "true" ]]; then
      echo "ERROR: replica container exited unexpectedly. Last 50 log lines:" >&2
      docker logs --tail 50 "${REPLICA_NAME}" >&2 || true
      FAILED=1
      exit 1
    fi

    if [[ "${ELAPSED}" -ge "${TIMEOUT_SECS}" ]]; then
      echo "ERROR: timed out after ${TIMEOUT_MIN}m waiting for replica to finish syncing" >&2
      FAILED=1
      exit 1
    fi

    HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:9125/dao" || echo 000)"
    if [[ "${HTTP_CODE}" == "200" ]]; then
      break
    fi

    if [[ $(( NOW_TS - LAST_PROGRESS_TS )) -ge 60 ]]; then
      LAST_PROGRESS_TS=${NOW_TS}
      HEIGHT_MATCH="$(docker logs --tail 200 "${REPLICA_NAME}" 2>&1 | grep -oE 'height[": ]+[0-9]{7}' | tail -1 || true)"
      log "still syncing... elapsed=${ELAPSED}s ${HEIGHT_MATCH:+(last seen: ${HEIGHT_MATCH})}"
    fi

    sleep 15
  done

  log "Replica synced after ${ELAPSED}s."
else
  # Same wait loop, generalized: wait until every replica returns 200 on
  # /dao, or the overall --timeout elapses. Each replica is checked and
  # logged individually; a replica that's already synced is skipped on
  # later passes.
  log "Waiting for ${MULTI_OPERATOR} replicas to sync (timeout ${TIMEOUT_MIN}m)..."
  TIMEOUT_SECS=$(( TIMEOUT_MIN * 60 ))
  START_TS=$(date +%s)
  LAST_PROGRESS_TS=${START_TS}
  ELAPSED=0

  REPLICA_SYNCED=()
  i=1
  while [[ "${i}" -le "${MULTI_OPERATOR}" ]]; do
    REPLICA_SYNCED+=(0)
    i=$(( i + 1 ))
  done

  while true; do
    NOW_TS=$(date +%s)
    ELAPSED=$(( NOW_TS - START_TS ))

    ALL_SYNCED=1
    i=1
    while [[ "${i}" -le "${MULTI_OPERATOR}" ]]; do
      idx=$(( i - 1 ))
      if [[ "${REPLICA_SYNCED[idx]}" -eq 1 ]]; then
        i=$(( i + 1 ))
        continue
      fi

      name="${REPLICA_NAMES[idx]}"
      port="$((9124 + i))"

      if [[ "$(docker inspect -f '{{.State.Running}}' "${name}" 2>/dev/null || echo false)" != "true" ]]; then
        echo "ERROR: replica-${i} container (${name}) exited unexpectedly. Last 50 log lines:" >&2
        docker logs --tail 50 "${name}" >&2 || true
        FAILED=1
        exit 1
      fi

      HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${port}/dao" || echo 000)"
      if [[ "${HTTP_CODE}" == "200" ]]; then
        REPLICA_SYNCED[idx]=1
      else
        ALL_SYNCED=0
      fi

      i=$(( i + 1 ))
    done

    if [[ "${ALL_SYNCED}" -eq 1 ]]; then
      break
    fi

    if [[ "${ELAPSED}" -ge "${TIMEOUT_SECS}" ]]; then
      echo "ERROR: timed out after ${TIMEOUT_MIN}m waiting for replicas to finish syncing" >&2
      FAILED=1
      exit 1
    fi

    if [[ $(( NOW_TS - LAST_PROGRESS_TS )) -ge 60 ]]; then
      LAST_PROGRESS_TS=${NOW_TS}
      i=1
      while [[ "${i}" -le "${MULTI_OPERATOR}" ]]; do
        idx=$(( i - 1 ))
        if [[ "${REPLICA_SYNCED[idx]}" -eq 0 ]]; then
          name="${REPLICA_NAMES[idx]}"
          HEIGHT_MATCH="$(docker logs --tail 200 "${name}" 2>&1 | grep -oE 'height[": ]+[0-9]{7}' | tail -1 || true)"
          log "replica-${i} still syncing... elapsed=${ELAPSED}s ${HEIGHT_MATCH:+(last seen: ${HEIGHT_MATCH})}"
        fi
        i=$(( i + 1 ))
      done
    fi

    sleep 15
  done

  log "All ${MULTI_OPERATOR} replicas synced after ${ELAPSED}s."
fi

# ----------------------------------------------------------------------------
# 8. Diff phase (up to 3 attempts, 30s apart — tip skew between replica and
#    prod causes transient diffs: /dao includes each DAO's current height and
#    configBoxId, so a block landing between the two fetches flips it. We
#    retry the *whole round* rather than per-endpoint so a round is judged on
#    a consistent view.)
#
# Sets DIFF_PASS, TOTAL, MATCHES, DIFF_FAILED_EPS as a side effect so the
# report step below (and a second round in --restart mode) can use them.
# ----------------------------------------------------------------------------
run_diff_phase() {
  log "Running diff phase..."

  local max_attempts=3
  local attempt=1
  DIFF_PASS=0
  TOTAL=0
  MATCHES=0
  DIFF_FAILED_EPS=()

  while [[ "${attempt}" -le "${max_attempts}" ]]; do
    log "Diff attempt ${attempt}/${max_attempts}"

    rm -rf "${RUN_DIR}/responses"
    mkdir -p "${RUN_DIR}/responses/replica" "${RUN_DIR}/responses/prod"

    local prod_dao_body dao_keys
    prod_dao_body="$(curl -s "${PROD_STATE}/dao" || true)"
    dao_keys="$(printf '%s' "${prod_dao_body}" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("\n".join(d.keys()))' 2>/dev/null || true)"

    local endpoints=("/health" "/dao")
    while IFS= read -r key; do
      [[ -z "${key}" ]] && continue
      endpoints+=(
        "/dao/${key}/config"
        "/dao/${key}/treasury"
        "/dao/${key}/proposals"
        "/stake/${key}"
      )
    done <<<"${dao_keys}"

    TOTAL=0
    MATCHES=0
    DIFF_FAILED_EPS=()

    for ep in "${endpoints[@]}"; do
      name="$(sanitize_endpoint "${ep}")"
      fetch_and_normalize "http://localhost:9125${ep}" "${RUN_DIR}/responses/replica/${name}" "${ep}"
      fetch_and_normalize "${PROD_STATE}${ep}" "${RUN_DIR}/responses/prod/${name}" "${ep}"
      TOTAL=$(( TOTAL + 1 ))
      if diff -q "${RUN_DIR}/responses/replica/${name}" "${RUN_DIR}/responses/prod/${name}" >/dev/null 2>&1; then
        MATCHES=$(( MATCHES + 1 ))
      else
        DIFF_FAILED_EPS+=("${ep}")
      fi
    done

    if [[ "${MATCHES}" -eq "${TOTAL}" ]]; then
      DIFF_PASS=1
      return
    fi

    if [[ "${attempt}" -lt "${max_attempts}" ]]; then
      log "Attempt ${attempt}: ${MATCHES}/${TOTAL} endpoints match, retrying in 30s..."
      sleep 30
    fi
    attempt=$(( attempt + 1 ))
  done
}

# Prints a PASS/FAIL report for the current DIFF_PASS/TOTAL/MATCHES/DIFF_FAILED_EPS
# and, on failure, sets FAILED=1 and exits 1.
report_diff_phase() {
  local label="$1"
  if [[ "${DIFF_PASS}" -eq 1 ]]; then
    log "REPLAY REGRESSION (${label}): PASS ${MATCHES}/${TOTAL} endpoints identical"
  else
    echo "REPLAY REGRESSION (${label}): differences found. Diffs (prod vs replica, head -60 each):" >&2
    for ep in "${DIFF_FAILED_EPS[@]}"; do
      name="$(sanitize_endpoint "${ep}")"
      echo "--- ${ep} ---" >&2
      diff -u "${RUN_DIR}/responses/prod/${name}" "${RUN_DIR}/responses/replica/${name}" 2>&1 | head -60 >&2 || true
    done
    echo "REPLAY REGRESSION (${label}): FAIL $(( TOTAL - MATCHES ))/${TOTAL} endpoints differ" >&2
    FAILED=1
    exit 1
  fi
}

# --multi-operator variant of run_diff_phase: fetches prod plus every
# replica and, per round, runs two kinds of comparisons for every endpoint —
# (a) replica-1 vs prod (the regular regression check) and (b) replica-i vs
# replica-1 for i=2..N (the determinism check: independent operators must
# converge on identical state). A round only passes if every comparison
# matches; otherwise the whole round is retried, same as run_diff_phase.
#
# Sets DIFF_PASS, TOTAL, MATCHES, MULTI_DIFF_FAILED_LABELS,
# MULTI_DIFF_FAILED_EPS as a side effect for report_diff_phase_multi below.
run_diff_phase_multi() {
  log "Running diff phase (multi-operator, ${MULTI_OPERATOR} replicas)..."

  local max_attempts=3
  local attempt=1
  local i idx port ep name
  DIFF_PASS=0
  TOTAL=0
  MATCHES=0
  MULTI_DIFF_FAILED_LABELS=()
  MULTI_DIFF_FAILED_EPS=()

  while [[ "${attempt}" -le "${max_attempts}" ]]; do
    log "Diff attempt ${attempt}/${max_attempts}"

    rm -rf "${RUN_DIR}/responses"
    mkdir -p "${RUN_DIR}/responses/prod"
    i=1
    while [[ "${i}" -le "${MULTI_OPERATOR}" ]]; do
      mkdir -p "${RUN_DIR}/responses/replica-${i}"
      i=$(( i + 1 ))
    done

    local prod_dao_body dao_keys
    prod_dao_body="$(curl -s "${PROD_STATE}/dao" || true)"
    dao_keys="$(printf '%s' "${prod_dao_body}" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("\n".join(d.keys()))' 2>/dev/null || true)"

    local endpoints=("/health" "/dao")
    while IFS= read -r key; do
      [[ -z "${key}" ]] && continue
      endpoints+=(
        "/dao/${key}/config"
        "/dao/${key}/treasury"
        "/dao/${key}/proposals"
        "/stake/${key}"
      )
    done <<<"${dao_keys}"

    # Fetch prod and every replica for every endpoint before comparing, so
    # all comparisons in this round are judged on a consistent set of fetches.
    for ep in "${endpoints[@]}"; do
      name="$(sanitize_endpoint "${ep}")"
      fetch_and_normalize "${PROD_STATE}${ep}" "${RUN_DIR}/responses/prod/${name}" "${ep}"
      i=1
      while [[ "${i}" -le "${MULTI_OPERATOR}" ]]; do
        port="$((9124 + i))"
        fetch_and_normalize "http://localhost:${port}${ep}" "${RUN_DIR}/responses/replica-${i}/${name}" "${ep}"
        i=$(( i + 1 ))
      done
    done

    TOTAL=0
    MATCHES=0
    MULTI_DIFF_FAILED_LABELS=()
    MULTI_DIFF_FAILED_EPS=()

    for ep in "${endpoints[@]}"; do
      name="$(sanitize_endpoint "${ep}")"

      # (a) replica-1 vs prod — the existing regression check.
      TOTAL=$(( TOTAL + 1 ))
      if diff -q "${RUN_DIR}/responses/replica-1/${name}" "${RUN_DIR}/responses/prod/${name}" >/dev/null 2>&1; then
        MATCHES=$(( MATCHES + 1 ))
      else
        MULTI_DIFF_FAILED_LABELS+=("prod vs replica-1")
        MULTI_DIFF_FAILED_EPS+=("${ep}")
      fi

      # (b) replica-i vs replica-1 for i=2..N — the determinism check.
      i=2
      while [[ "${i}" -le "${MULTI_OPERATOR}" ]]; do
        TOTAL=$(( TOTAL + 1 ))
        if diff -q "${RUN_DIR}/responses/replica-1/${name}" "${RUN_DIR}/responses/replica-${i}/${name}" >/dev/null 2>&1; then
          MATCHES=$(( MATCHES + 1 ))
        else
          MULTI_DIFF_FAILED_LABELS+=("replica-1 vs replica-${i}")
          MULTI_DIFF_FAILED_EPS+=("${ep}")
        fi
        i=$(( i + 1 ))
      done
    done

    if [[ "${MATCHES}" -eq "${TOTAL}" ]]; then
      DIFF_PASS=1
      return
    fi

    if [[ "${attempt}" -lt "${max_attempts}" ]]; then
      log "Attempt ${attempt}: ${MATCHES}/${TOTAL} comparisons match, retrying in 30s..."
      sleep 30
    fi
    attempt=$(( attempt + 1 ))
  done
}

# Prints a PASS/FAIL report for the current DIFF_PASS/TOTAL/MATCHES/
# MULTI_DIFF_FAILED_LABELS/MULTI_DIFF_FAILED_EPS and, on failure, sets
# FAILED=1 and exits 1.
report_diff_phase_multi() {
  if [[ "${DIFF_PASS}" -eq 1 ]]; then
    log "REPLAY REGRESSION (multi-operator): PASS ${MATCHES}/${TOTAL} endpoint comparisons identical across ${MULTI_OPERATOR} replicas + prod"
    return
  fi

  echo "REPLAY REGRESSION (multi-operator): differences found. Diffs (head -60 each):" >&2
  local idx=0 label ep name other
  for label in "${MULTI_DIFF_FAILED_LABELS[@]}"; do
    ep="${MULTI_DIFF_FAILED_EPS[idx]}"
    name="$(sanitize_endpoint "${ep}")"
    echo "--- ${label}: ${ep} ---" >&2
    if [[ "${label}" == "prod vs replica-1" ]]; then
      diff -u "${RUN_DIR}/responses/prod/${name}" "${RUN_DIR}/responses/replica-1/${name}" 2>&1 | head -60 >&2 || true
    else
      other="${label#replica-1 vs }"
      diff -u "${RUN_DIR}/responses/replica-1/${name}" "${RUN_DIR}/responses/${other}/${name}" 2>&1 | head -60 >&2 || true
    fi
    idx=$(( idx + 1 ))
  done
  echo "REPLAY REGRESSION (multi-operator): FAIL $(( TOTAL - MATCHES ))/${TOTAL} comparisons differ" >&2
  FAILED=1
  exit 1
}

if [[ "${MULTI_OPERATOR}" -eq 0 ]]; then
  run_diff_phase
  report_diff_phase "initial sync"
else
  run_diff_phase_multi
  report_diff_phase_multi
fi

# ----------------------------------------------------------------------------
# 9. Blocked-broadcast count (informational)
# ----------------------------------------------------------------------------
BLOCKED_COUNT="$(docker logs "${PROXY_NAME}" 2>&1 | grep -c 'BLOCKED transaction broadcast' || true)"
BLOCKED_COUNT="${BLOCKED_COUNT:-0}"
log "REPLAY REGRESSION: ${BLOCKED_COUNT} broadcast attempts blocked"

# ----------------------------------------------------------------------------
# 10. --restart mode: restart the replica, confirm it resumes from a persisted
#     checkpoint instead of falling back to a full archive replay, and re-run the
#     diff phase against the restarted replica.
# ----------------------------------------------------------------------------
if [[ "${RESTART}" -eq 1 ]]; then
  log "Restart mode: restarting replica ${REPLICA_NAME}..."
  RESTART_T0=$(date +%s)
  docker restart "${REPLICA_NAME}" >/dev/null

  while true; do
    NOW_TS=$(date +%s)
    RESTART_ELAPSED=$(( NOW_TS - RESTART_T0 ))

    if [[ "$(docker inspect -f '{{.State.Running}}' "${REPLICA_NAME}" 2>/dev/null || echo false)" != "true" ]]; then
      echo "ERROR: replica container exited unexpectedly after restart. Last 50 log lines:" >&2
      docker logs --tail 50 "${REPLICA_NAME}" >&2 || true
      FAILED=1
      exit 1
    fi

    if [[ "${RESTART_ELAPSED}" -ge "${RESTART_TIMEOUT_SECS}" ]]; then
      echo "ERROR: timed out after ${RESTART_TIMEOUT_SECS}s waiting for replica to become ready after restart" >&2
      FAILED=1
      exit 1
    fi

    HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:9125/ready" || echo 000)"
    if [[ "${HTTP_CODE}" == "200" ]]; then
      break
    fi

    sleep 2
  done

  RESTART_ELAPSED=$(( $(date +%s) - RESTART_T0 ))
  log "restart to ready: ${RESTART_ELAPSED}s"

  # Capture to a file first: with pipefail, `docker logs | grep -q` reports failure
  # when grep exits early and docker logs dies of SIGPIPE, even though the line exists.
  RESTART_LOG="${RUN_DIR}/logs/replica-after-restart.log"
  docker logs "${REPLICA_NAME}" >"${RESTART_LOG}" 2>&1 || true
  if ! grep -q "Restored state at height" "${RESTART_LOG}"; then
    echo "ERROR: replica did not resume from a persisted checkpoint after restart - it fell back to a full archive replay instead. Restore-related log lines:" >&2
    grep -i "restor\|checkpoint" "${RESTART_LOG}" >&2 || true
    FAILED=1
    exit 1
  fi
  # ...and it must not have replayed the archive on top of the restored state.
  if awk '/Restored state at height/{f=1; next} f && /transaction_archive\//{c++} END{exit (c>0)}' "${RESTART_LOG}"; then
    log "Confirmed replica resumed from a persisted checkpoint without archive replay."
  else
    echo "ERROR: replica restored a checkpoint but then replayed the transaction archive on top of it." >&2
    FAILED=1
    exit 1
  fi

  log "Re-running diff phase against the restarted replica..."
  run_diff_phase
  report_diff_phase "after restart"

  BLOCKED_COUNT="$(docker logs "${PROXY_NAME}" 2>&1 | grep -c 'BLOCKED transaction broadcast' || true)"
  BLOCKED_COUNT="${BLOCKED_COUNT:-0}"
  log "REPLAY REGRESSION: PASS (initial sync + restart) - ${BLOCKED_COUNT} broadcast attempts blocked total"
fi

exit 0
