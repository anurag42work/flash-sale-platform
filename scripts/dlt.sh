#!/usr/bin/env bash
# List and replay dead-letter topics (incident 004). Runs the Kafka CLI tools inside the kafka container.
#
# Usage:
#   scripts/dlt.sh topics                              all DLT topics with their message counts
#   scripts/dlt.sh list   <topic.DLT>                  partition, offset, key, value of every message
#   scripts/dlt.sh show   <topic.DLT> <partition> <offset>   one message with its headers (error, original offset)
#   scripts/dlt.sh replay <topic.DLT> <partition> <offset>   publish that one message again to <topic>, same key
#   scripts/dlt.sh replay-all <topic.DLT>              publish every message not replayed by replay-all before
#
# Replaying is safe to repeat: every consumer dedupes by eventId (processed_events). Fix the cause first,
# otherwise the message just lands on the DLT again.
# Limitation: the console tools treat one line as one message, so a value containing a newline can't be replayed
# with this script (our services only publish single-line JSON).
#
# Compose file: deploy/compose.infra.yml (laptop). On the VM: COMPOSE_FILE=deploy/compose.yml scripts/dlt.sh ...
set -euo pipefail

cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1 # Git Bash: don't rewrite /opt/kafka/... into Windows paths

COMPOSE_FILE=${COMPOSE_FILE:-deploy/compose.infra.yml}
BOOTSTRAP=localhost:9092
BIN=/opt/kafka/bin
SEP='|DLT-KEY|' # between key and value on the console; never appears in our keys (UUIDs)
# Keep the CLI tools small inside the 768 MB broker container.
KAFKA_ENV=(-e KAFKA_HEAP_OPTS=-Xmx128m)

kafka() {
  docker compose -f "$COMPOSE_FILE" exec -T "${KAFKA_ENV[@]}" kafka "$@"
}

usage() {
  sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
}

require_dlt() {
  case "$1" in
    *.DLT) ;;
    *) echo "Not a dead-letter topic (must end in .DLT): $1" >&2; exit 1 ;;
  esac
}

consume() { # extra args for kafka-console-consumer
  kafka "$BIN/kafka-console-consumer.sh" --bootstrap-server "$BOOTSTRAP" --timeout-ms 5000 "$@" 2>/dev/null || true
}

produce_to() { # <topic>; reads "key<SEP>value" lines from stdin
  kafka "$BIN/kafka-console-producer.sh" --bootstrap-server "$BOOTSTRAP" --topic "$1" \
    --property parse.key=true --property "key.separator=$SEP"
}

cmd=${1:-}
case "$cmd" in
  topics)
    for t in $(kafka "$BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --list | grep '\.DLT$' || true); do
      count=$(kafka "$BIN/kafka-get-offsets.sh" --bootstrap-server "$BOOTSTRAP" --topic "$t" \
        | awk -F: '{ sum += $3 } END { print sum + 0 }')
      echo "$t  messages=$count"
    done
    ;;
  list)
    [ $# -eq 2 ] || usage
    require_dlt "$2"
    consume --topic "$2" --from-beginning \
      --property print.partition=true --property print.offset=true --property print.key=true
    ;;
  show)
    [ $# -eq 4 ] || usage
    require_dlt "$2"
    consume --topic "$2" --partition "$3" --offset "$4" --max-messages 1 \
      --property print.key=true --property print.headers=true \
      --property headers.separator=$'\n  '
    ;;
  replay)
    [ $# -eq 4 ] || usage
    require_dlt "$2"
    target=${2%.DLT}
    line=$(consume --topic "$2" --partition "$3" --offset "$4" --max-messages 1 \
      --property print.key=true --property "key.separator=$SEP")
    [ -n "$line" ] || { echo "No message at $2 partition $3 offset $4" >&2; exit 1; }
    printf '%s\n' "$line" | produce_to "$target"
    echo "Replayed $2 partition $3 offset $4 -> $target (key ${line%%"$SEP"*})"
    ;;
  replay-all)
    [ $# -eq 2 ] || usage
    require_dlt "$2"
    target=${2%.DLT}
    # A consumer group remembers what replay-all already sent, so running it twice doesn't send everything again.
    lines=$(consume --topic "$2" --group "dlt-replay" --from-beginning \
      --property print.key=true --property "key.separator=$SEP")
    if [ -z "$lines" ]; then
      echo "Nothing new to replay on $2"
      exit 0
    fi
    printf '%s\n' "$lines" | produce_to "$target"
    echo "Replayed $(printf '%s\n' "$lines" | wc -l | tr -d ' ') message(s) from $2 -> $target"
    ;;
  *)
    usage
    ;;
esac
