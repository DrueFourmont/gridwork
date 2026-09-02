# syntax=docker/dockerfile:1

# ---- build stage -----------------------------------------------------------
# Full JDK, used only to compile. None of it reaches the final image.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Copy the build definition before the sources. Docker caches each layer, so
# dependency resolution is only redone when a build file actually changes,
# not on every source edit.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY domain/build.gradle.kts domain/
COPY core/build.gradle.kts core/
COPY api/build.gradle.kts api/
COPY worker/build.gradle.kts worker/
RUN ./gradlew --no-daemon :api:dependencies --quiet || true

COPY domain domain
COPY core core
COPY api api
RUN ./gradlew --no-daemon :api:bootJar -x test

# Unpack the fat jar into Spring Boot's layers. Dependencies change rarely and
# application code changes constantly, so splitting them means a redeploy
# pushes a few hundred kilobytes instead of the whole jar.
WORKDIR /staging
RUN cp /src/api/build/libs/*.jar app.jar && java -Djarmode=tools -jar app.jar extract --layers --launcher

# ---- runtime stage ---------------------------------------------------------
# Alpine, not the Ubuntu based 21-jre, for one measured reason: the Ubuntu
# variant produced a 435.5 MB image, over the 300 MB budget in CLAUDE.md.
# Alpine lands at 287.8 MB. Same JDK vendor, musl instead of glibc.
FROM eclipse-temurin:21-jre-alpine AS runtime

# curl is only here for the HEALTHCHECK below. Nothing else needs it.
RUN apk add --no-cache curl

# Never run as root. If the process is compromised it should not own the
# filesystem it is standing on.
RUN addgroup -S -g 1001 gridwork \
    && adduser -S -u 1001 -G gridwork -h /app -s /sbin/nologin gridwork

WORKDIR /app
COPY --from=build --chown=gridwork:gridwork /staging/app/dependencies/ ./
COPY --from=build --chown=gridwork:gridwork /staging/app/spring-boot-loader/ ./
COPY --from=build --chown=gridwork:gridwork /staging/app/snapshot-dependencies/ ./
COPY --from=build --chown=gridwork:gridwork /staging/app/application/ ./

USER gridwork
EXPOSE 8080

# Readiness, not plain health: this answers "can this container take traffic",
# which is the question an orchestrator is actually asking.
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
