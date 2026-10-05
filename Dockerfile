# syntax=docker/dockerfile:1

########## Stage 1 — build (JDK) ##########
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY app/src/Main.java .
RUN javac Main.java

########## Stage 2 — runtime (JRE only, non-root) ##########
FROM eclipse-temurin:21-jre
WORKDIR /app
ARG APP_VERSION=1.0.0
ENV APP_VERSION=${APP_VERSION}
COPY --from=build /src/Main.class .
EXPOSE 8080
USER 1000
ENTRYPOINT ["java", "Main"]