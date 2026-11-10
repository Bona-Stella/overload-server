#!/bin/sh
set -eu

if [ "${#OVERLOAD_GIF_PROXY_TOKEN}" -ne 64 ]; then
    echo "Invalid GIF proxy token length" >&2
    exit 1
fi
case "$OVERLOAD_GIF_PROXY_TOKEN" in
    *[!0-9a-f]*) echo "GIF proxy token must use lowercase hex" >&2; exit 1 ;;
esac

if ! printf '%s\n' "$OVERLOAD_GIF_BACKEND_UPSTREAM" | grep -Eq '^[a-zA-Z0-9.-]+:[0-9]{1,5}$' ||
    [ "$(printf '%s' "$OVERLOAD_GIF_BACKEND_UPSTREAM" | wc -l)" -ne 0 ]; then
    echo "GIF Backend upstream must be a hostname and port" >&2
    exit 1
fi
