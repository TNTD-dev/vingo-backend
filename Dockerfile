# Build: sinh code từ contract + đóng gói jar. Test chạy ở CI (./mvnw verify), không chạy lại ở đây.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY contracts/ contracts/
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests && cp target/vingo-backend-*.jar /app.jar

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 vingo
USER vingo
WORKDIR /app
COPY --from=build /app.jar app.jar
EXPOSE 8080
# SPRING_PROFILES_ACTIVE=api hoặc worker
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
