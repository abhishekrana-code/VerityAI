#!/usr/bin/env bash
# Remove local test-residue accounts and, optionally, the PDFs they left behind.
#
# Verification runs and the E2E suites register throwaway accounts
# (e2e@verityai.test, docker@verityai.test, intel-live-<id>@example.com, ...).
# They are harmless but clutter the user table, and deleting their rows outside
# the app leaves their uploads on the uploads volume for the hourly orphan sweep.
#
# Dry run by default — prints what matches and changes nothing:
#   ./scripts/purge-test-accounts.sh
#   ./scripts/purge-test-accounts.sh --apply
#   ./scripts/purge-test-accounts.sh --apply --purge-files
#
# Only two families are matched, never a hand-picked address:
#   *  *@verityai.test                     (reserved test domain)
#   *  <prefix>-<8 hex>@example.com        where prefix is one of the check
#                                          accounts the suites generate
# A real account is not matched even if it shares a prefix, because the
# example.com form requires the generated 8-hex suffix.
#
# The stack must be running (docker compose up -d). Container names and the
# in-container upload path can be overridden with DB_CONTAINER, APP_CONTAINER
# and UPLOAD_DIR.

set -euo pipefail

DB_CONTAINER=${DB_CONTAINER:-verityai-postgres}
APP_CONTAINER=${APP_CONTAINER:-verityai-backend}
UPLOAD_DIR=${UPLOAD_DIR:-/var/lib/verityai/uploads}

FILTER="(u.email LIKE '%@verityai.test'
         OR u.email ~ '^(intel-live|kg-live|f8-live|f8-neg|e2e|live|reset|prof)-[0-9a-f]{8}@example\\.com$')"

# The help is this file's header comment, printed up to the first blank line.
usage() {
    sed -n '2,/^$/{s/^# \{0,1\}//;p}' "$0"
}

apply=false
purge_files=false
for arg in "$@"; do
    case "$arg" in
        --apply) apply=true ;;
        --purge-files) purge_files=true ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $arg" >&2; usage >&2; exit 2 ;;
    esac
done

require_running() {
    if [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || true)" != "true" ]; then
        echo "Container $1 is not running. Start the stack first: docker compose up -d" >&2
        exit 1
    fi
}

# Terse, machine-readable psql: one value per line, or pipe-separated columns.
sql() {
    docker exec -i "$DB_CONTAINER" sh -c \
        'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -t -A'
}

require_running "$DB_CONTAINER"
require_running "$APP_CONTAINER"

# Match once and reuse the quoted list everywhere, so a row registered between
# statements cannot change what is reported or deleted.
targets=$(sql <<SQL
SELECT string_agg(quote_literal(u.email), ',' ORDER BY u.email)
FROM users u
WHERE $FILTER;
SQL
)

if [ -z "$targets" ]; then
    echo "No test-residue accounts found."
    exit 0
fi

accounts=$(sql <<SQL
SELECT u.email || '|' || (SELECT count(*) FROM documents d WHERE d.user_id = u.id)
    || '|' || (SELECT count(*) FROM conversations c WHERE c.user_id = u.id)
FROM users u
WHERE u.email IN ($targets)
ORDER BY u.email;
SQL
)

files=$(sql <<SQL
SELECT d.stored_filename
FROM documents d JOIN users u ON u.id = d.user_id
WHERE u.email IN ($targets);
SQL
)

account_count=0
document_count=0
printf '%-46s %5s %6s\n' ACCOUNT DOCS CONVS
while IFS='|' read -r email docs convos; do
    [ -z "$email" ] && continue
    account_count=$((account_count + 1))
    document_count=$((document_count + docs))
    printf '%-46s %5s %6s\n' "$email" "$docs" "$convos"
done <<< "$accounts"

file_count=0
while IFS= read -r name; do
    [ -z "$name" ] && continue
    if docker exec "$APP_CONTAINER" sh -c "test -f '$UPLOAD_DIR/$name'"; then
        file_count=$((file_count + 1))
    fi
done <<< "$files"

echo
echo "$account_count account(s), $document_count document(s), $file_count upload file(s) on disk."
echo

if ! $apply; then
    echo "Dry run — nothing deleted. Re-run with --apply to remove these accounts."
    if [ "$file_count" -gt 0 ]; then
        if $purge_files; then
            echo "--purge-files has no effect without --apply."
        else
            echo "Add --purge-files to delete their PDFs; otherwise the hourly orphan sweep clears them."
        fi
    fi
    exit 0
fi

# One statement, so the session and user deletes cannot land half-applied.
sql <<SQL
WITH doomed AS (
    SELECT id, email FROM users WHERE email IN ($targets)
), removed_sessions AS (
    DELETE FROM spring_session WHERE principal_name IN (SELECT email FROM doomed)
)
DELETE FROM users WHERE id IN (SELECT id FROM doomed);
SQL

echo "Deleted $account_count account(s) and their rows."

if ! $purge_files; then
    if [ "$file_count" -gt 0 ]; then
        echo "Left $file_count upload file(s); the hourly orphan sweep removes them."
    fi
    exit 0
fi

removed=0
while IFS= read -r name; do
    [ -z "$name" ] && continue
    # Its document rows went with the account, so a file still bearing this
    # name is an orphan; the hourly sweep would remove it as well.
    if docker exec "$APP_CONTAINER" sh -c "test -f '$UPLOAD_DIR/$name'"; then
        if docker exec "$APP_CONTAINER" sh -c "rm -f '$UPLOAD_DIR/$name'"; then
            removed=$((removed + 1))
        else
            echo "Could not remove $name" >&2
        fi
    fi
done <<< "$files"

echo "Removed $removed leftover upload file(s)."
