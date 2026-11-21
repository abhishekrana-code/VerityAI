#!/bin/sh
# Compose creates named volumes owned by root, which the unprivileged app user
# could not write to. Fix ownership of the two writable directories (top level
# only: already-uploaded files stay readable) and drop root before starting the
# JVM. Without this the container would have to run as root.
set -e

if [ "$(id -u)" = "0" ]; then
    chown verity:verity "$UPLOAD_DIR" "$EMBEDDINGS_CACHE_DIR"
    exec setpriv --reuid=10001 --regid=10001 --clear-groups "$@"
fi

exec "$@"
