# syntax=docker/dockerfile:1

# ---- build: compile with the JDK, then split the fat jar into layers ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dependencies change less often than code: resolve them in their own cached layer.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -ntp -q -DskipTests package \
 && java -Djarmode=tools -jar target/app.jar extract --layers --launcher --destination extracted

# ---- runtime: JRE only, non-root, one image layer per jar layer ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

# Least- to most-frequently changing, so a code-only change re-pushes just the last small layer.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- "http://localhost:${PORT:-8080}/actuator/health/readiness" >/dev/null || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
