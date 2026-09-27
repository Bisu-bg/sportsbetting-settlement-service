FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle lombok.config ./
COPY gradle gradle
COPY src src
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew --no-daemon clean check bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-Duser.home=/tmp"
COPY --from=build /workspace/build/libs/sportsbetting-settlement-service.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
