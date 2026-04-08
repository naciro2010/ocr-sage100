#!/bin/sh
set -e

echo "=== ReconDoc MADAEF - Starting ==="
echo "PORT=${PORT:-8080}"

# Convert Railway's DATABASE_URL to Spring Boot JDBC format
if [ -n "$DATABASE_URL" ]; then
  echo "DATABASE_URL detected, converting to JDBC format..."
  case "$DATABASE_URL" in
    jdbc:*) JDBC_URL="$DATABASE_URL" ;;
    postgresql://*) JDBC_URL="jdbc:$DATABASE_URL" ;;
    postgres://*) JDBC_URL="jdbc:postgresql://${DATABASE_URL#postgres://}" ;;
    *) JDBC_URL="$DATABASE_URL" ;;
  esac

  if echo "$JDBC_URL" | grep -q '@'; then
    USERINFO=$(echo "$JDBC_URL" | sed 's|jdbc:postgresql://\([^@]*\)@.*|\1|')
    HOSTPART=$(echo "$JDBC_URL" | sed 's|jdbc:postgresql://[^@]*@\(.*\)|\1|')

    export DATABASE_USERNAME="${DATABASE_USERNAME:-$(echo "$USERINFO" | cut -d: -f1)}"
    export DATABASE_PASSWORD="${DATABASE_PASSWORD:-$(echo "$USERINFO" | cut -d: -f2-)}"

    JDBC_URL="jdbc:postgresql://$HOSTPART"
  fi

  export SPRING_DATASOURCE_URL="$JDBC_URL"
  export SPRING_DATASOURCE_USERNAME="${DATABASE_USERNAME}"
  export SPRING_DATASOURCE_PASSWORD="${DATABASE_PASSWORD}"

  # Log DB host (no password)
  echo "JDBC URL: $(echo "$JDBC_URL" | sed 's|//.*@|//***@|')"
else
  echo "WARNING: No DATABASE_URL set, using defaults"
fi

# JVM: fast startup, container-aware memory
DEFAULT_JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss256k"

# Pass Railway's PORT to Spring Boot
SPRING_PORT_OPTS=""
if [ -n "$PORT" ]; then
  SPRING_PORT_OPTS="-Dserver.port=$PORT"
  echo "Binding to Railway PORT=$PORT"
else
  echo "No PORT env var, defaulting to 8080"
fi

echo "Starting JVM..."
exec java $DEFAULT_JAVA_OPTS $JAVA_OPTS $SPRING_PORT_OPTS -jar app.jar
