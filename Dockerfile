FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -B -DskipTests clean package

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/target/*-jar-with-dependencies.jar /app/app.jar

RUN groupadd --gid 10001 kojima \
    && useradd --uid 10001 --gid kojima --no-create-home --shell /usr/sbin/nologin kojima \
    && install -d --owner=kojima --group=kojima --mode=0700 /app/data

ENV JAVA_OPTS=""
ENV BOT_DB_PATH="/app/data/bot-data.db"

USER kojima

CMD ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
