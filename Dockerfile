
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

RUN apt-get update \
	&& apt-get install --no-install-recommends -y curl \
	&& rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
	CMD curl --fail --silent --show-error "http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health" || exit 1

USER 10001

ENTRYPOINT ["java", "-jar", "app.jar"]
