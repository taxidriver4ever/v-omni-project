FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline

COPY src ./src
RUN mvn clean package -DskipTests

# 2. 这里保持 21，前后统一
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

ENV JAVA_OPTS="-XX:+UseContainerSupport -Xms2g -Xmx4g -XX:+UseG1GC"

EXPOSE 18281
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]