# syntax=docker/dockerfile:1
#
# Builds paideia-state together with its paideia-sdk dependency.
# Build context must be the parent directory (containing paideia-sdk/ and paideia-state/):
#
#   cd paideia-state && docker compose build
#   # or: docker build -f paideia-state/Dockerfile -t paideia-state-main .   (from the workspace root)
#
# All third-party dependencies are released artifacts on Maven Central
# (plasma-toolkit 1.1.0 -> ergo-appkit 6.0.1 -> sigma-state 6.0.6); nothing is built from source.
# Only the source trees are copied in, so local target/, .git and data directories never
# reach the build. A .dockerignore at the workspace root additionally keeps the context small.

FROM sbtscala/scala-sbt:eclipse-temurin-jammy-11.0.22_7_1.9.9_2.12.18 AS build

ARG SDK_VERSION=1.0.0-rc5-SNAPSHOT
ENV SBT_OPTS="-Xmx3g -Dsbt.color=false -Dsbt.supershell=false"

# ---- paideia-sdk -> ~/.ivy2/local ----
WORKDIR /build/sdk
COPY paideia-sdk/project/build.properties paideia-sdk/project/plugins.sbt project/
COPY paideia-sdk/build.sbt .
RUN --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.sbt \
    sbt update
COPY paideia-sdk/src src
RUN --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.sbt \
    sbt "set version := \"${SDK_VERSION}\"" publishLocal

# ---- paideia-state ----
WORKDIR /build/state
COPY paideia-state/project/build.properties paideia-state/project/plugins.sbt project/
COPY paideia-state/build.sbt .
RUN --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.sbt \
    sbt update
COPY paideia-state/app app
COPY paideia-state/conf conf
COPY paideia-state/public public
RUN --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.sbt \
    sbt stage


# ---- runtime ----
FROM eclipse-temurin:11-jre-jammy

WORKDIR /opt/docker
COPY --from=build /build/state/target/universal/stage /opt/docker
RUN chmod +x bin/paideia-state-main

EXPOSE 9000
ENTRYPOINT ["bin/paideia-state-main", "-Dpidfile.path=/dev/null"]
