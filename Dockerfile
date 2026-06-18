# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY gradlew gradlew.bat ./
COPY gradle gradle/
COPY build.gradle settings.gradle ./
COPY src src/

RUN chmod +x gradlew \
    && ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN groupadd -r reservepay && useradd -r -g reservepay reservepay

COPY --chown=reservepay:reservepay --from=build /app/build/libs/*.jar app.jar

USER reservepay

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
