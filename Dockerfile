FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew :server:installDist --no-daemon

FROM eclipse-temurin:21-jre
RUN groupadd --system vault && useradd --system --gid vault --home-dir /data vault && mkdir -p /data && chown vault:vault /data
WORKDIR /app
COPY --from=build /workspace/server/build/install/server/ /app/
USER vault
ENV VAULT_BIND=0.0.0.0 VAULT_PORT=7070 VAULT_DATABASE=/data/documentvault.db
EXPOSE 7070
CMD ["/app/bin/server"]
