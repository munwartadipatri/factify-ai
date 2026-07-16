FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN addgroup --system factify && adduser --system --ingroup factify factify

COPY --from=build /workspace/target/factify-backend-0.0.1-SNAPSHOT.jar /app/factify-backend.jar

USER factify

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/factify-backend.jar"]
