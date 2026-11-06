#!/usr/bin/env bash
# Boot the NeoForge server in the current directory until it finishes loading (or fails),
# then stop it and copy logs to $1. Writes $1/RESULT = started | crashed | timeout.
set -u
OUT="$1"; mkdir -p "$OUT"
TIMEOUT="${SERVER_TIMEOUT:-900}"

ls mods > "$OUT/mods.txt"
rm -f console.log stdin.fifo && mkfifo stdin.fifo
# Keep the fifo open so the server doesn't see EOF on stdin.
sleep infinity > stdin.fifo &
HOLD=$!
chmod +x run.sh
./run.sh nogui < stdin.fifo > console.log 2>&1 &
PID=$!

result=timeout
for ((i = 0; i < TIMEOUT; i += 5)); do
  sleep 5
  if grep -qE 'Done \([0-9.]+s\)! For help' console.log; then result=started; break; fi
  if ! kill -0 "$PID" 2>/dev/null; then result=crashed; break; fi
done

if [ "$result" = started ]; then
  # Let a few ticks run so tick-time mixins get exercised, then stop cleanly.
  sleep 20
  echo "stop" > stdin.fifo
  for _ in $(seq 1 60); do kill -0 "$PID" 2>/dev/null || break; sleep 2; done
fi
kill "$PID" 2>/dev/null; kill "$HOLD" 2>/dev/null
grep -qE '^---- Minecraft Crash Report ----|Exception in server tick loop' console.log logs/latest.log 2>/dev/null && [ "$result" != started ] && result=crashed

cp console.log "$OUT/" 2>/dev/null
cp logs/latest.log "$OUT/" 2>/dev/null
cp logs/debug.log "$OUT/" 2>/dev/null
cp -r crash-reports "$OUT/" 2>/dev/null
echo "$result" > "$OUT/RESULT"
echo "server result: $result"
