FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -q -B dependency:go-offline

COPY src src
RUN ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
ENV JAVA_OPTS="-XX:+UseContainerSupport"
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
