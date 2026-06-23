# ---------------------------------------------------------------------------
# STAGE 1: Build & Packaging Environment (JDK)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /build

ENV SBT_OPTS="-Dsbt.supershell=false -Dsbt.color=false"

RUN apt-get update && apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.asdf.list && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt

COPY build.sbt .
COPY project/ ./project/
RUN sbt update
COPY src/ ./src
RUN sbt -mem 2048 assembly

# ---------------------------------------------------------------------------
# STAGE 2: Pristine Production Runtime (JRE)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN useradd -r -u 1001 -g root banking_simulator
RUN mkdir -p /app/logs /app/data /app/checkpoints && \
    chown -R banking_simulator:root /app

USER banking_simulator

COPY --from=builder /build/target/scala-3.3.7/transaction-simulator.jar ./app.jar

EXPOSE 9090

ENTRYPOINT ["java", \
            "-XX:+UseG1GC", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-jar", "app.jar"]