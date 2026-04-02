#!/bin/sh
# Extract DNS resolver from the container's resolv.conf so nginx can
# dynamically re-resolve upstream hostnames on every request.
# This is critical on Railway where backend IPs change on redeploy.
RESOLVER=$(awk '/^nameserver/{print $2; exit}' /etc/resolv.conf)
export RESOLVER="${RESOLVER:-8.8.8.8}"
echo "nginx: using DNS resolver $RESOLVER"

# Delegate to the default nginx entrypoint (envsubst + start)
exec /docker-entrypoint.sh nginx -g "daemon off;"
