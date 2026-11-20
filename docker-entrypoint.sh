#!/bin/sh
# Starts the API as the unprivileged "app" user. Railway mounts volumes owned by root, so when the container
# starts as root this gives DATA_DIR to "app" first and then drops privileges (setpriv, from util-linux).
# Started with --user (not root), it runs as that user and DATA_DIR must already be writable.
set -eu
DATA_DIR="${DATA_DIR:-/data}"
if [ "$(id -u)" = "0" ]; then
    mkdir -p "$DATA_DIR"
    chown -R app:app "$DATA_DIR"
    exec setpriv --reuid=app --regid=app --init-groups -- java -jar /app/app.jar "$@"
fi
exec java -jar /app/app.jar "$@"
