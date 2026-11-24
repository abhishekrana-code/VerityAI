#!/usr/bin/env bash
# Run the live answer-quality evaluation: real corpus, real model calls.
#
# Every other backend test mocks the LLM, so none of them can notice a prompt
# change, a model swap or a provider deprecation that degrades grounding. This
# script uploads three short gold PDFs, asks five questions (three answerable,
# two unanswerable) and prints a per-question table plus a JSON report.
#
# It spends your API credits and needs Docker, so it does nothing until asked:
#   ./scripts/eval-answers.sh              # show the plan, run nothing
#   ./scripts/eval-answers.sh --run        # run the evaluation
#
# Credentials come from the environment, or from ./.env when present. The key
# is checked for presence only and never printed.
#
# Knobs (environment variables):
#   EVAL_MODEL=...            model override for the run (default: app default)
#   EVAL_EMBEDDINGS=off       keyword-only retrieval, much faster first run
#   EVAL_TIMEOUT_SECONDS=180  corpus processing deadline
#
# The run fails if any question fails; details are in the report at
# backend/target/eval/answer-quality.json.

set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
report="$root/backend/target/eval/answer-quality.json"

usage() {
    sed -n '2,/^$/{s/^# \{0,1\}//;p}' "$0"
}

run=false
for arg in "$@"; do
    case "$arg" in
        --run) run=true ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $arg" >&2; usage >&2; exit 2 ;;
    esac
done

if [ -f "$root/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$root/.env"
    set +a
fi

if [ -z "${LLM_API_KEY:-}${GROQ_API_KEY:-}" ]; then
    echo "No LLM_API_KEY (or GROQ_API_KEY) in the environment or ./.env." >&2
    echo "The live evaluation needs a real model endpoint; nothing was run." >&2
    exit 1
fi

if ! $run; then
    cat <<'PLAN'
Live answer-quality evaluation — nothing was run.

  Corpus     3 generated PDFs: 2024-annual-report, 2024-board-notes, product-memo
  Questions  5: 42% revenue growth, the board's 38% target, Lantern's launch
              date, plus CEO compensation and Berlin headcount (unanswerable)
  Checks     expected document cited, expected fact present, honest refusal
  Cost       one model call per question (5 calls); Docker for PostgreSQL

Re-run with --run to spend those calls.
PLAN
    exit 0
fi

cd "$root/backend"
EVAL_ENABLED=true ./mvnw -q -Dtest=AnswerQualityEvaluationTest -DfailIfNoTests=false test

echo
echo "Report: $report"
