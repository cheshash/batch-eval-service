FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY common/pom.xml common/
COPY api/pom.xml api/
COPY worker/pom.xml worker/
RUN mvn -B -pl common,api,worker -am dependency:go-offline
COPY . .
RUN mvn -B -pl api,worker -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/api/target/api-0.1.0-SNAPSHOT.jar /app/api.jar
COPY --from=build /app/worker/target/worker-0.1.0-SNAPSHOT.jar /app/worker.jar
COPY --from=build /app/config /app/config
EXPOSE 8000 9000
