FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
COPY king-ai-api/pom.xml king-ai-api/pom.xml
RUN mvn -B -q -pl king-ai-api -am dependency:go-offline

COPY king-ai-api/src king-ai-api/src
RUN mvn -B -q -pl king-ai-api -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine

# OCR escape hatch: tesseract is only invoked when KING_AI_OCR_ENABLED=true.
RUN apk add --no-cache tesseract-ocr tesseract-ocr-data-eng tesseract-ocr-data-chi_sim

RUN addgroup -S king && adduser -S king -G king
WORKDIR /app

COPY --from=build --chown=king:king /build/king-ai-api/target/king-ai-api.jar app.jar

USER king
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
