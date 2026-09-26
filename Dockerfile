# Build: docker compose build   |   Beh: pozri docs/NASADENIE.md
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN sh ./gradlew --no-daemon :platform-web:bootJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home /app app
WORKDIR /app
COPY --from=build /src/platform-web/build/libs/platform-web.jar app.jar
USER app
EXPOSE 8080
ENV TZ=Europe/Bratislava
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
