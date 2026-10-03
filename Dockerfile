# =====================================================================
#  AIIMS Kalyani - IT Event Portal
#  Multi-stage build: Maven builder -> JRE runtime
# =====================================================================

# ---------- Stage 1: compile ----------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Resolve dependencies first so this layer is cached across code-only changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# NOTE: -DskipTests is MANDATORY, not optional. ItApplicationTests is a
# @SpringBootTest that boots the full Spring context and therefore needs a
# reachable MySQL. Without this flag the build fails (or worse, mutates a DB).
RUN mvn -B -DskipTests package \
 && cp target/*.jar /build/app.jar

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:25-jre
WORKDIR /app

# curl is required by the container HEALTHCHECK below.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -r -u 10001 -M aiims \
 && mkdir -p /app/uploads \
 && chown -R aiims:aiims /app

COPY --from=build --chown=aiims:aiims /build/app.jar /app/app.jar

USER aiims
EXPOSE 8080

# Heap sizing - override in .env as JAVA_OPTS without rebuilding.
ENV JAVA_OPTS="-Xms256m -Xmx768m"

# /login is permitAll in SecurityConfig, so this returns 200 when the app is up.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS -o /dev/null http://127.0.0.1:8080/login || exit 1

# exec so java becomes PID 1 and receives SIGTERM for graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]