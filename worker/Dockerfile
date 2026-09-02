# syntax=docker/dockerfile:1

# ---- build stage -----------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY domain/build.gradle.kts domain/
COPY core/build.gradle.kts core/
COPY api/build.gradle.kts api/
COPY worker/build.gradle.kts worker/
RUN ./gradlew --no-daemon :worker:dependencies --quiet || true

COPY domain domain
COPY core core
COPY worker worker
RUN ./gradlew --no-daemon :worker:bootJar -x test

WORKDIR /staging
RUN cp /src/worker/build/libs/*.jar app.jar && java -Djarmode=tools -jar app.jar extract --layers --launcher

# ---- runtime stage ---------------------------------------------------------
# Alpine for the same reason as the api image: it keeps the artefact under
# the 300 MB budget. Measured, not assumed.
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S -g 1001 gridwork \
    && adduser -S -u 1001 -G gridwork -h /app -s /sbin/nologin gridwork

WORKDIR /app
COPY --from=build --chown=gridwork:gridwork /staging/app/dependencies/ ./
COPY --from=build --chown=gridwork:gridwork /staging/app/spring-boot-loader/ ./
COPY --from=build --chown=gridwork:gridwork /staging/app/snapshot-dependencies/ ./
COPY --from=build --chown=gridwork:gridwork /staging/app/application/ ./

USER gridwork

# The worker serves no HTTP, so there is no endpoint to curl. "Is the JVM
# still alive" is the only honest question a container level check can ask
# here. Phase 4 replaces this with a real check once there is a queue
# consumer whose liveness can be observed, most likely last-poll-timestamp
# exposed through a small actuator surface.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD pgrep -f org.springframework.boot.loader.launch.JarLauncher > /dev/null || exit 1

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
