#!/bin/zsh
# Foxgrade — one-command setup for the FREE local AI backend.
#
# Foxgrade works with no AI at all (the deterministic ruleset handles ~79% of changes). This script
# adds the free local model that handles most of the rest — no API key, no account, no usage limits,
# no data leaving your machine.
#
#   ./setup-free-ai.sh            # default: qwen2.5-coder:1.5b (Apache 2.0, ~1 GB)
#   Benchmarked 4/4 on Foxgrade's actual tasks — same score as the 9 GB 14b, 2x faster.
#   Because our tables supply the facts, model SIZE barely matters; it only has to APPLY them.
#   ./setup-free-ai.sh 14b        # better quality, ~9 GB, needs ~16 GB RAM
#
# Licensing note: qwen2.5-coder 7B/14B are Apache 2.0 (redistributable). The 3B variant is under the
# Qwen-Research licence and is deliberately NOT offered here.
set -u
SIZE="${1:-1.5b}"
MODEL="qwen2.5-coder:${SIZE}"

echo "Foxgrade free-AI setup"
echo "  model: $MODEL"

# --- 1. hardware sanity ---
RAM_GB=$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 ))
FREE_GB=$(df -g / 2>/dev/null | tail -1 | awk '{print $4}')
NEED_DISK=$([[ "$SIZE" == "14b" ]] && echo 10 || [[ "$SIZE" == "7b" ]] && echo 6 || echo 3)
NEED_RAM=$([[ "$SIZE" == "14b" ]] && echo 16 || [[ "$SIZE" == "7b" ]] && echo 8 || echo 4)
echo "  your machine: ${RAM_GB} GB RAM, ${FREE_GB} GB free disk"
if (( RAM_GB < NEED_RAM )); then
  echo "  ⚠ $MODEL wants ~${NEED_RAM} GB RAM. Try: ./setup-free-ai.sh 7b   (or skip local AI entirely —"
  echo "    Foxgrade still does ~79% of the work with no AI at all.)"
fi
if (( FREE_GB < NEED_DISK )); then
  echo "  ✗ Not enough disk: need ~${NEED_DISK} GB, have ${FREE_GB} GB. Free some space and re-run."
  exit 1
fi

# --- 2. ollama ---
if ! command -v ollama >/dev/null; then
  echo "  Ollama is not installed. It is the free local model runner."
  echo "  Install it from https://ollama.com/download (or: brew install ollama), then re-run this script."
  exit 1
fi
echo "  ✅ ollama: $(command -v ollama)"

# --- 3. service up? ---
if ! curl -s --max-time 3 http://localhost:11434/api/tags >/dev/null 2>&1; then
  echo "  starting the ollama service..."
  (ollama serve >/dev/null 2>&1 &)
  for i in {1..15}; do sleep 1; curl -s --max-time 2 http://localhost:11434/api/tags >/dev/null 2>&1 && break; done
fi
curl -s --max-time 3 http://localhost:11434/api/tags >/dev/null 2>&1 \
  && echo "  ✅ service responding on :11434" \
  || { echo "  ✗ could not reach ollama on :11434 — run 'ollama serve' in another terminal."; exit 1; }

# --- 4. model ---
if ollama list 2>/dev/null | grep -q "^${MODEL}"; then
  echo "  ✅ $MODEL already downloaded"
else
  echo "  downloading $MODEL (one time; several GB — this takes a while)..."
  ollama pull "$MODEL" || { echo "  ✗ download failed"; exit 1; }
fi

# keep the model resident between calls; a cold reload costs 30-60s EVERY call
launchctl setenv OLLAMA_KEEP_ALIVE 30m 2>/dev/null

# --- 5. prove it works end to end ---
echo "  verifying..."
RESP=$(curl -s --max-time 300 http://localhost:11434/api/generate -H 'content-type: application/json' \
  -d "{\"model\":\"$MODEL\",\"prompt\":\"Reply with exactly: OK\",\"stream\":false,\"options\":{\"temperature\":0}}" \
  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{console.log((JSON.parse(s).response||"").trim())}catch{console.log("")}})')
if [[ "$RESP" == *OK* ]]; then
  echo "  ✅ free AI is working"
  echo
  echo "Use it:"
  echo "  node foxgrade.mjs <mod-src> --from 1.16.5 --to 26.2 --foxai"
  echo "  (or omit --ollama to use an API key / Claude subscription if you have one)"
else
  echo "  ⚠ model did not answer as expected — it may still be loading. Try the command again shortly."
fi
