# Stage 1 — build
FROM eclipse-temurin:17-jdk AS builder

RUN apt-get update && apt-get install -y curl && \
    curl -sL "https://github.com/sbt/sbt/releases/download/v1.12.11/sbt-1.12.11.tgz" | tar xz -C /usr/local && \
    ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt

WORKDIR /app
COPY project ./project
COPY build.sbt .
# Download dependencies first (cached layer)
RUN sbt update

COPY . .
RUN sbt stage

# Stage 2 — run
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=builder /app/target/universal/stage ./

ENV APPLICATION_SECRET=""
ENV PORT=9000

EXPOSE 9000

CMD ["sh", "-c", "bin/life-dashboard-api -Dhttp.port=$PORT -Dplay.http.secret.key=$APPLICATION_SECRET"]
