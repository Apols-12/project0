# Stage 1: Cache Gradle dependencies
FROM gradle:8.14.3-jdk AS build

WORKDIR /app

COPY gradle ./gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .
COPY src ./src
RUN gradle shadowJar

FROM openjdk:23-jdk

WORKDIR /app

COPY src ./src
COPY myh2db.mv.db .
COPY --from=build /app/build/libs/myaws-all.jar .
EXPOSE 5000

CMD ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "myaws-all.jar"]