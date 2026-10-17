# Production image: builds the jar, then runs it with the New Relic Java agent. Started by compose.yml.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
# Tests run on your PC before pushing; the cache mount keeps Maven's downloads between builds.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests
# The zip ships a default newrelic.yml; the NEW_RELIC_* variables from .env override it.
ADD https://download.newrelic.com/newrelic/java-agent/newrelic-agent/current/newrelic-java.zip /tmp/newrelic-java.zip
RUN cd /opt && jar xf /tmp/newrelic-java.zip

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /opt/newrelic /opt/newrelic
COPY --from=build /build/target/*.jar app.jar
# The subtitle cache volume mounts here; made now so it belongs to the app user, not root.
RUN mkdir -p data/subtitles && chown -R ubuntu data
USER ubuntu
EXPOSE 8080
# The agent is attached only when a license key is set, so the app also runs without New Relic.
# 4xx answers (an expired login, a rate limit) are normal traffic, so only 5xx count as errors.
ENTRYPOINT ["sh", "-c", "exec java ${NEW_RELIC_LICENSE_KEY:+-javaagent:/opt/newrelic/newrelic.jar} -Dnewrelic.config.log_file_name=STDOUT -Dnewrelic.config.error_collector.ignore_status_codes=400-499 -jar app.jar"]
