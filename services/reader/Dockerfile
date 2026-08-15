FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /shared
COPY packages/shared/mvnw packages/shared/pom.xml ./
COPY packages/shared/.mvn .mvn
RUN ./mvnw -q -B dependency:go-offline
COPY packages/shared/src src
RUN ./mvnw -q -B install -DskipTests

WORKDIR /app
COPY services/reader/mvnw services/reader/pom.xml ./
COPY services/reader/.mvn .mvn
RUN ./mvnw -q -B dependency:go-offline

COPY services/reader/src src
RUN ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
ENV JAVA_OPTS="-XX:+UseContainerSupport"
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
