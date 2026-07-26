FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY . .
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:17-jre

RUN groupadd --system --gid 1001 casiq \
    && useradd --system --uid 1001 --gid casiq --home-dir /work casiq \
    && mkdir -p /work/data/attachments \
    && chown -R casiq:casiq /work

WORKDIR /work
COPY --from=build --chown=casiq:casiq /workspace/casiq-application/target/quarkus-app/lib/ /work/lib/
COPY --from=build --chown=casiq:casiq /workspace/casiq-application/target/quarkus-app/*.jar /work/
COPY --from=build --chown=casiq:casiq /workspace/casiq-application/target/quarkus-app/app/ /work/app/
COPY --from=build --chown=casiq:casiq /workspace/casiq-application/target/quarkus-app/quarkus/ /work/quarkus/

USER 1001
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/work/quarkus-run.jar"]
