# Build: docker build -t namma-ooru-food-server .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :server:buildFatJar --no-daemon -q

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/server/build/libs/*-all.jar /app/server.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
