# Multi-Stage: Maven baut, das Runtime-Image bleibt schlank (JRE 25).
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/loader.jar ./loader.jar
# --enable-native-access: sqlite-jdbc lädt seine native Bibliothek; ab JDK 25
# ist das ohne explizite Freigabe ein Warning (künftig ein Fehler).
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/loader.jar"]
