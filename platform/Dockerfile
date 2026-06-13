# Build stage: maven wrapper + dependency cache layer, then the fat jar
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -q dependency:go-offline
COPY src src
RUN ./mvnw -q package -DskipTests -Dspotless.check.skip=true

# Runtime stage: JRE only
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/platform-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
