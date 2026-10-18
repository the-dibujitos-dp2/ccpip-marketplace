# CCPIP-23: imagen del backend. Build en JDK, ejecucion en JRE con usuario sin privilegios.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
# Las pruebas ya corren en el pipeline de CI antes de construir la imagen
RUN ./mvnw -B -q package -DskipTests -Djacoco.skip=true

FROM eclipse-temurin:21-jre-alpine
# Paquetes de Alpine al dia aunque la imagen base aun no se haya reconstruido: el deploy bloquea CVEs
# HIGH/CRITICAL que ya tienen parche (p. ej. CVE-2026-77214 en libexpat 2.8.5, corregida en 2.9.0).
RUN apk upgrade --no-cache \
    && addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
