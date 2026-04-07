#!/bin/sh

# Convert Railway's DATABASE_URL (postgresql://user:pass@host:port/db)
# to Spring Boot JDBC format (jdbc:postgresql://host:port/db) + separate credentials
if [ -n "$DATABASE_URL" ]; then
  # Add jdbc: prefix if missing
  case "$DATABASE_URL" in
    jdbc:*) JDBC_URL="$DATABASE_URL" ;;
    postgresql://*) JDBC_URL="jdbc:$DATABASE_URL" ;;
    postgres://*) JDBC_URL="jdbc:postgresql://${DATABASE_URL#postgres://}" ;;
    *) JDBC_URL="$DATABASE_URL" ;;
  esac

  # Extract username and password from URL if present (format: jdbc:postgresql://user:pass@host:port/db)
  if echo "$JDBC_URL" | grep -q '@'; then
    USERINFO=$(echo "$JDBC_URL" | sed 's|jdbc:postgresql://\([^@]*\)@.*|\1|')
    HOSTPART=$(echo "$JDBC_URL" | sed 's|jdbc:postgresql://[^@]*@\(.*\)|\1|')

    export DATABASE_USERNAME="${DATABASE_USERNAME:-$(echo "$USERINFO" | cut -d: -f1)}"
    export DATABASE_PASSWORD="${DATABASE_PASSWORD:-$(echo "$USERINFO" | cut -d: -f2-)}"

    # Remove query params for clean JDBC URL, then re-add if needed
    JDBC_URL="jdbc:postgresql://$HOSTPART"
  fi

  export SPRING_DATASOURCE_URL="$JDBC_URL"
  export SPRING_DATASOURCE_USERNAME="${DATABASE_USERNAME}"
  export SPRING_DATASOURCE_PASSWORD="${DATABASE_PASSWORD}"

  # Only reset DB if explicitly requested (e.g. for schema-breaking migrations)
  if [ "$DB_RESET_ON_DEPLOY" = "true" ]; then
    echo "=== Resetting database (DROP SCHEMA public) ==="
    PGPASSWORD="$DATABASE_PASSWORD" psql \
      "$(echo "$DATABASE_URL" | sed 's|^jdbc:||')" \
      -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" \
      2>&1 && echo "=== Database reset OK ===" \
           || echo "=== Database reset SKIPPED (DB may not be reachable yet) ==="
  fi
fi

# JVM defaults: use container-aware memory (75% of available), GC logging
DEFAULT_JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -Xlog:gc:stdout:time,level"

exec java $DEFAULT_JAVA_OPTS $JAVA_OPTS -jar app.jar
