#!/usr/bin/env bash
#
# One-command on-sale stampede against a live service.
#   ./burst.sh <BASE_URL>
#
# Tunables (env vars, with defaults):
#   SEATS=200 USERS=500 HOT_REQUESTS=500 SPREAD_REQUESTS=2000 IDEMPOTENCY_REPLAYS=50 CONC=100
#   ADMIN_TOKEN=admin-local PRICE=25000
# For the full ~20k burst:  SPREAD_REQUESTS=20000 CONC=200 ./burst.sh <BASE_URL>
#
# Prints the outcome distribution (confirmed / declined-by-reason / 5xx) and the final
# reconciliation (available + held + confirmed == total_seats).

set -euo pipefail

BASE_URL="${1:-}"
if [[ -z "$BASE_URL" ]]; then echo "usage: ./burst.sh <BASE_URL>"; exit 1; fi
BASE_URL="${BASE_URL%/}"

ADMIN_TOKEN="${ADMIN_TOKEN:-admin-local}"
SEATS="${SEATS:-200}"
USERS="${USERS:-500}"
HOT_REQUESTS="${HOT_REQUESTS:-500}"
SPREAD_REQUESTS="${SPREAD_REQUESTS:-2000}"
IDEMPOTENCY_REPLAYS="${IDEMPOTENCY_REPLAYS:-50}"
CONC="${CONC:-100}"
PRICE="${PRICE:-25000}"
HOT_SEAT="A1"

command -v curl >/dev/null 2>&1 || { echo "curl is required"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
say(){ printf '\n\033[1m%s\033[0m\n' "$*"; }
json_str(){ grep -oE "\"$2\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" <<<"$1" | head -1 | sed -E 's/.*:[[:space:]]*"//; s/"$//'; }
json_num(){ grep -oE "\"$2\"[[:space:]]*:[[:space:]]*[0-9]+" <<<"$1" | head -1 | sed -E 's/.*:[[:space:]]*//'; }

say "1) Creating a fresh show with $SEATS seats ..."
seats_arr="$(printf '"A%s",' $(seq 1 "$SEATS"))"; seats_arr="[${seats_arr%,}]"
create_body="{\"name\":\"burst-$(date +%s)-$RANDOM\",\"seats\":${seats_arr},\"price_paise\":${PRICE}}"
create_resp="$(curl -sS -X POST "$BASE_URL/shows" -H "X-Admin-Token: $ADMIN_TOKEN" -H "Content-Type: application/json" -d "$create_body")"
SHOW_ID="$(json_str "$create_resp" id)"
[[ -z "$SHOW_ID" ]] && { echo "failed to create show -> $create_resp"; exit 1; }
echo "   show_id=$SHOW_ID"
export BASE_URL SHOW_ID

say "2) Minting $USERS user tokens (parallel) ..."
seq 1 "$USERS" | xargs -P"$CONC" -I{} sh -c \
  'curl -sS -X POST "$0/auth/token" -H "Content-Type: application/json" -d "{\"user_id\":\"u{}\"}"' "$BASE_URL" \
  > "$WORK/tokraw" 2>/dev/null || true
grep -oE '"token":"[^"]*"' "$WORK/tokraw" | sed -E 's/.*:"//; s/"//' > "$WORK/tokens"
TOKENS=(); while IFS= read -r line; do [[ -n "$line" ]] && TOKENS+=("$line"); done < "$WORK/tokens"
[[ ${#TOKENS[@]} -eq 0 ]] && { echo "failed to mint tokens"; exit 1; }
echo "   minted ${#TOKENS[@]} tokens"

# reserve_one <token> <seat> <idem_key> -> prints "<http> <reason>"
reserve_one(){
  local token="$1" seat="$2" key="$3" out http body reason
  out="$(curl -sS -m 30 -w $'\n%{http_code}' -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
        -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
        -H "Idempotency-Key: $key" -d "{\"seats\":[\"$seat\"]}" 2>/dev/null || printf '\n000')"
  http="${out##*$'\n'}"; body="${out%$'\n'*}"
  if   [[ "$http" == "201" ]]; then reason="confirmed"
  elif [[ "$http" == "200" ]]; then reason="idempotent_replay"
  else reason="$(grep -oE '"code":"[^"]*"' <<<"$body" | head -1 | sed -E 's/.*:"//; s/"//')"; [[ -z "$reason" ]] && reason="http_$http"; fi
  echo "$http $reason"
}
export -f reserve_one

: > "$WORK/codes"
run_phase(){ xargs -P"$CONC" -n3 bash -c 'reserve_one "$1" "$2" "$3"' _ < "$1" >> "$WORK/codes"; }

say "3) HOT-SEAT STORM: $HOT_REQUESTS buyers fighting for one seat ($HOT_SEAT) ..."
: > "$WORK/jobs_hot"
for i in $(seq 1 "$HOT_REQUESTS"); do
  echo "${TOKENS[$((RANDOM % ${#TOKENS[@]}))]} $HOT_SEAT hot-$i-$RANDOM" >> "$WORK/jobs_hot"
done
run_phase "$WORK/jobs_hot"
hot_wins=$(head -n "$HOT_REQUESTS" "$WORK/codes" | grep -c '^201 ' || true)

say "4) IDEMPOTENCY: $IDEMPOTENCY_REPLAYS concurrent retries with the SAME key (seat A2) ..."
idem_key="replay-$(date +%s)-$RANDOM"
: > "$WORK/jobs_idem"
for i in $(seq 1 "$IDEMPOTENCY_REPLAYS"); do echo "${TOKENS[0]} A2 $idem_key" >> "$WORK/jobs_idem"; done
run_phase "$WORK/jobs_idem"

say "5) GENERAL STAMPEDE: $SPREAD_REQUESTS reserves across all seats (random users/seats) ..."
: > "$WORK/jobs_spread"
for i in $(seq 1 "$SPREAD_REQUESTS"); do
  echo "${TOKENS[$((RANDOM % ${#TOKENS[@]}))]} A$(( (RANDOM % SEATS) + 1 )) spread-$i-$RANDOM" >> "$WORK/jobs_spread"
done
run_phase "$WORK/jobs_spread"

say "================ OUTCOME DISTRIBUTION (http + reason) ================"
sort "$WORK/codes" | uniq -c | sort -rn

total=$(wc -l < "$WORK/codes" | tr -d ' ')
fivexx=$(grep -cE '^5[0-9][0-9] ' "$WORK/codes" || true)
echo "--------------------------------------------------------------------"
echo "total requests : $total"
echo "5xx responses  : $fivexx   <- correctness bar: MUST be 0"
echo "hot-seat 201s  : $hot_wins   <- correctness bar: MUST be exactly 1"

say "================ FINAL RECONCILIATION (GET /shows/{id}) ================"
state="$(curl -sS "$BASE_URL/shows/$SHOW_ID")"
avail=$(json_num "$state" available); held=$(json_num "$state" held)
conf=$(json_num "$state" confirmed); total_seats=$(json_num "$state" total_seats)
sum=$(( ${avail:-0} + ${held:-0} + ${conf:-0} ))
echo "available=$avail  held=$held  confirmed=$conf  total_seats=$total_seats"
if [[ "$sum" == "$total_seats" ]]; then
  echo "RECONCILED OK: available + held + confirmed == total_seats ($sum)"
else
  echo "RECONCILE MISMATCH: sum=$sum total=$total_seats"; exit 2
fi
