#!/usr/bin/env bash
# Send the sample MetricRequest to a locally running ebean-insight-server.
# Tracks startEventTime across iterations to simulate a real client emitting
# disjoint delta windows.
# Usage:  ./send-sample.sh [count]
set -euo pipefail

INSIGHT_URL="${INSIGHT_URL:-http://localhost:8091/api/ingest/metrics}"
COUNT="${1:-1}"
HERE="$(cd "$(dirname "$0")" && pwd)"

LAST_EVENT=$(($(date +%s) * 1000))
for i in $(seq 1 "$COUNT"); do
  EVENT_TIME=$(($(date +%s) * 1000))
  jq --argjson t "$EVENT_TIME" --argjson s "$LAST_EVENT" \
     '.eventTime = $t | .startEventTime = $s' "$HERE/sample-metric-request.json" \
    | curl -sS -X POST -H 'Content-Type: application/json' --data-binary @- "$INSIGHT_URL"
  echo "  -> sent #$i (window=[$LAST_EVENT, $EVENT_TIME])"
  LAST_EVENT=$EVENT_TIME
  [ "$i" -lt "$COUNT" ] && sleep 5
done

