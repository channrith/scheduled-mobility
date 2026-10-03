# syntax=docker/dockerfile:1
# One image per deployable:
#   docker build --build-arg MODULE=core-app -t mobility/core-app .
#   docker build --build-arg MODULE=location-service -t mobility/location-service .
# Tests are not run here; CI runs ./mvnw verify before building images.

ARG MODULE=core-app

FROM eclipse-temurin:21-jdk-noble AS build
ARG MODULE
WORKDIR /build
COPY . .
# The cache mount keeps ~/.m2 (dependencies and the Maven distribution) between builds.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -pl ${MODULE} -am package -Dmaven.test.skip=true \
 && cp ${MODULE}/target/${MODULE}-*.jar application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-noble
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
# Least to most frequently changing, so a code change only replaces the last layer.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER app
# Heap as a share of the container memory limit; crash (and get restarted) rather than limp on after OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "application.jar"]
