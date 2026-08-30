# paideia-state

Play/Scala service that tracks Paideia DAO state on the Ergo blockchain. Depends on
[paideia-sdk](../paideia-sdk), which is built together with it.

## Deployment

Images are built by GitHub Actions (`.github/workflows/docker.yml`) and published to
`ghcr.io/paideiadao/paideia-state`: every push to `main` updates `:latest`, git tags `v*`
get their own tag, and every build gets `:sha-<short>`. The workflow checks out
`paideia-sdk` at the revision pinned in `SDK_REF` (bump it when the SDK changes; a manual
"Run workflow" can override it).

On the server (checkout of this repo with a `.env`, variables: see `conf/application.conf`):

```bash
git pull
docker compose pull
docker compose up -d
```

State lives in the bind-mounted `daoconfigs/`, `stakingStates/`, `proposals/`,
`transaction_archive/` and `errors/` directories, so it survives image updates. To roll
back, point `image:` at a previous `sha-` tag and `docker compose up -d` again.

## Build with Docker locally

The `Dockerfile` is a multi-stage build that compiles `../paideia-sdk`, publishes it
locally inside the build container, then packages this service. The build context is the
workspace root (the directory containing both `paideia-sdk/` and `paideia-state/`);
`docker-compose.yml` is already configured for that.

```bash
docker compose build     # builds ghcr.io/paideiadao/paideia-state:latest locally
docker compose up -d     # needs a .env file (variables: see conf/application.conf)
```

Or without compose, from the workspace root:

```bash
docker build -f paideia-state/Dockerfile -t paideia-state-main .
```

Dependency downloads are kept in BuildKit cache mounts, so rebuilds after source changes
are fast. Only `src/`, `app/`, `conf/`, `public/` and the sbt build files are copied into the
build; copying `.dockerignore.workspace` to the workspace root as `.dockerignore` is optional
but keeps the build context tiny (the CI workflow does this). `--build-arg SDK_VERSION=...` overrides the version the SDK is published as
(must match `build.sbt`).

## Local sbt build

Requires JDK 11 and sbt 1.x. Publish the SDK first with the version `build.sbt` expects:

```bash
(cd ../paideia-sdk && sbt 'set version := "1.0.0-rc4-SNAPSHOT"' publishLocal)
sbt compile
sbt run
```

The service listens on port 9000 (mapped to 9124 by compose) and exposes `/health`.
