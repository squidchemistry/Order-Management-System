#!/usr/bin/env bash
# End-to-end smoke test against a running instance: the same flow as requests.http, with the
# expected HTTP status checked at every step. Needs only bash and curl.
#   scripts/smoke.sh                       # http://localhost:8080
#   scripts/smoke.sh https://<service>.onrender.com
set -uo pipefail

BASE="${1:-http://localhost:8080}"
KEY="smoke-$(date +%s)-$RANDOM"
CUSTOMER="cust-$KEY"
BODY='{"customerId":"'"$CUSTOMER"'","currency":"INR","items":[
  {"sku":"SKU-KB-01","productName":"Mechanical keyboard","quantity":2,"unitPrice":3499.00},
  {"sku":"SKU-MS-02","productName":"Wireless mouse","quantity":1,"unitPrice":799.50}]}'
pass=0
fail=0
OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT

# check <expected status> <label> <curl args...>; the response body is left in $OUT
check() {
  local expected="$1" label="$2"
  shift 2
  local got
  got="$(curl -s -o "$OUT" -w '%{http_code}' "$@")"
  if [[ "$got" == "$expected" ]]; then
    pass=$((pass + 1))
    printf 'PASS  %-3s %s\n' "$got" "$label"
  else
    fail=$((fail + 1))
    printf 'FAIL  %-3s %s (expected %s)\n      %s\n' "$got" "$label" "$expected" "$(head -c 300 "$OUT")"
  fi
}

field() { grep -o "\"$1\":[^,}]*" "$OUT" | head -1 | cut -d: -f2- | tr -d '"'; }

check 200 "liveness"  "$BASE/actuator/health/liveness"
check 200 "readiness" "$BASE/actuator/health/readiness"

check 201 "create order" -X POST "$BASE/api/orders" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d "$BODY"
ID="$(field id)"
TOTAL="$(field totalAmount)"
[[ "$TOTAL" == "7797.5" || "$TOTAL" == "7797.50" ]] && pass=$((pass + 1)) && echo "PASS      server-side total = $TOTAL" \
  || { fail=$((fail + 1)); echo "FAIL      server-side total = '$TOTAL' (expected 7797.50)"; }

check 200 "idempotent replay (same key, same body)" -X POST "$BASE/api/orders" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY"
[[ "$(field id)" == "$ID" ]] && pass=$((pass + 1)) && echo "PASS      replay returned the same id ($ID)" \
  || { fail=$((fail + 1)); echo "FAIL      replay returned id $(field id), expected $ID"; }

check 422 "same key, different body" -X POST "$BASE/api/orders" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d "${BODY/$CUSTOMER/$CUSTOMER-other}"

check 200 "get by id"         "$BASE/api/orders/$ID"
check 200 "list with filters" "$BASE/api/orders?customerId=$CUSTOMER&status=PENDING&size=5"

check 200 "confirm (If-Match \"0\")" -X POST "$BASE/api/orders/$ID/confirm" -H 'If-Match: "0"'
check 412 "cancel with stale If-Match \"0\"" -X POST "$BASE/api/orders/$ID/cancel" -H 'If-Match: "0"'
check 409 "confirm a CONFIRMED order" -X POST "$BASE/api/orders/$ID/confirm"
check 200 "cancel (If-Match \"1\")" -X POST "$BASE/api/orders/$ID/cancel" -H 'If-Match: "1"' \
  -H 'Content-Type: application/json' -d '{"reason":"smoke test"}'
check 409 "cancel a CANCELLED order" -X POST "$BASE/api/orders/$ID/cancel"

check 400 "invalid input" -X POST "$BASE/api/orders" -H 'Content-Type: application/json' \
  -d '{"customerId":"","currency":"rupees","items":[{"sku":"S","productName":"P","quantity":0,"unitPrice":-5}]}'
check 400 "malformed JSON" -X POST "$BASE/api/orders" -H 'Content-Type: application/json' -d '{"customerId":'
check 400 "unknown status filter" "$BASE/api/orders?status=SHIPPED"
check 404 "unknown order" "$BASE/api/orders/999999999"

check 200 "OpenAPI document" "$BASE/v3/api-docs"
check 200 "Swagger UI"       "$BASE/swagger-ui/index.html"

echo "----"
echo "$pass passed, $fail failed against $BASE"
[[ "$fail" -eq 0 ]]
