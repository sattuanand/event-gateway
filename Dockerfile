# Build stage
FROM gradle:8.10.2-jdk21-alpine AS build
WORKDIR /workspace
COPY settings.gradle build.gradle gradle.properties ./
COPY event-gateway/build.gradle event-gateway/build.gradle
COPY event-gateway/src event-gateway/src
RUN gradle :event-gateway:bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/event-gateway/build/libs/event-gateway.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
