# ---- build stage: resolves from Maven Central (clean, mirror-free) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -e -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -e -DskipTests clean package

# ---- run stage: slim JRE ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/seat-reserve.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
