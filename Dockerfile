
# syntax=docker/dockerfile:1

# Stage 1: Build
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

RUN chmod +x mvnw

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B dependency:go-offline
    
COPY config/checkstyle/ config/checkstyle/

COPY src/ src/

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

USER 10001

ENTRYPOINT ["java", "-jar", "app.jar"]
