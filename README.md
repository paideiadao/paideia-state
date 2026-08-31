# paideia-state

Play/Scala service that tracks Paideia DAO state on the Ergo blockchain. Depends on
[paideia-sdk](../paideia-sdk), which is built together with it.

## Genesis configuration

`conf/application.conf` holds only **operator-local** settings (node, explorer, ZMQ,
operator address, UI fees, bot behavior) — the values a party running its own instance
is expected to change. Everything that identifies the protocol instance lives in
`conf/genesis/paideia-mainnet.conf`: the genesis token IDs, the Paideia DAO key, the
sync start height, and the values that seed the initial DAO config AVL tree.

**Genesis values are immutable per deployment.** On-chain governance has since changed
some of them (the config box has been updated several times); the service converges by
replaying those updates during sync, starting from the genesis seed. Updating a genesis
value to its "current" on-chain value therefore breaks the deployment — the seeded tree
digest no longer matches genesis and the instance silently forks. Current values can be
queried at `GET /dao/<paideiaDaoKey>/config`.

### Running on testnet or bootstrapping a new protocol instance

1. Mint the genesis tokens on the target chain (origin NFT, DAO key NFT, DAO token
   supply, governance token, and the action/proposal/stake-state tokens) into a wallet
   you control.
2. Copy `conf/genesis/paideia-mainnet.conf` to a new file and fill in those token IDs,
   `networkType`, `syncStart` (current height) and `emission_start`. Point the
   `include` at the top of `application.conf` at your genesis file (or pass a full
   config via `-Dconfig.file`) and configure a node/explorer for the target network.
3. Start the service and call `POST /dao/bootstrap`. It assembles the genesis boxes
   from the config — Paideia origin (holding the DAO token supply), DAO origin, the
   Config box carrying the seeded config-tree digest, the staking contract boxes and
   the stake pool — and returns an unsigned transaction spending the pre-minted tokens
   from the provided `userAddresses`; sign and submit it.

Every party operating an instance of the same protocol deployment must use the exact
same genesis file.

## Replay regression test

`scripts/replay-regression.sh` is a behavior-preservation oracle: it runs a replica of
this service against a fresh copy of prod's replay state, lets it sync to tip, and diffs
its API responses against the live prod instance (the reference implementation) — the
same technique used to verify the sync-loss-NPE and consolidate-box-size fixes before
they shipped. A clean run means the change under test didn't alter observable DAO state.

```bash
scripts/replay-regression.sh [--no-build] [--keep] [--timeout MIN] [--data DIR] \
                              [--restart] [--restart-timeout SEC] \
                              [--multi-operator [N]]
```

- `--no-build` skips `docker compose build` and uses whatever `paideia-state:latest`
  image is already local.
- `--keep` keeps the run directory (copied state, container logs, saved API responses)
  and its diagnostics even when the run passes; by default only a failing run's
  directory is kept.
- `--timeout MIN` bounds how long to wait for the replica to finish syncing (default 45).
- `--data DIR` points at the prod data copy to replay from (default
  `/home/luivatra/develop/paideia/.replay-test`).
- `--restart` — after the normal (post-sync) diff passes, `docker restart` the replica,
  poll `/ready` until it comes back up (bounded by `--restart-timeout`), assert from its
  logs that it resumed from a persisted checkpoint (`Paideia.restoreState`) rather than
  falling back to a full archive replay, then re-run the diff phase against the
  restarted replica and report that result too. Exercises the state-persistence path
  end to end: without it the harness only ever tests a fresh-replay boot. Not supported
  together with `--multi-operator`.
- `--restart-timeout SEC` bounds how long to wait for the replica to become ready again
  after `--restart` (default 600).
- `--multi-operator [N]` — determinism mode: instead of one replica, run `N` replicas
  (2 or 3; default 3 when `N` is omitted) on the same shared network and tx-block proxy,
  each with its own fresh, non-shared copy of the replay state and a different
  `OPERATOR_ADDRESS`/`UI_FEE_ADDRESS`. Proves state evolution doesn't depend on which
  operator address a node runs with. Every replica's `/dao` must return 200 before the
  diff phase starts; the diff phase then checks, for every endpoint, both replica-1 vs
  prod (the usual regression check) and every other replica vs replica-1 (the
  determinism check) — a round only passes if all of those match. Mutually exclusive
  with `--restart`.

### Requirements

- The prod Ergo node (`192.168.1.137:9053`) and prod state service
  (`192.168.1.137:9124`) reachable on the LAN.
- `../paideia-sdk` checked out next to this repo (needed to build the image, skip with
  `--no-build` if you already have one).
- ~2GB free disk for the per-run copy of the replay state and container images.
- The data dir itself (`transaction_archive/`, `daoconfigs/`, `stakingStates/`,
  `proposals/`, `errors/`) is populated by an `rsync` from
  `luiserver:/opt/paideia/paideia-state-main` — see `../HANDOFF.md`. The harness always
  works on a fresh `cp -a` of that data per run, since the replica mutates it in place.
  `state/` is also copied if present, but prod doesn't keep one in that data dir today,
  so in practice the replica always starts from an empty `state/` and does a full replay
  on the first (non-`--restart`) sync — the normal case.

### Transaction-broadcast blocking

The replica signs and would otherwise broadcast the transactions it generates (consolidation,
proposal/vote processing, etc.) straight to the real Ergo node — unacceptable for a test run.
So the replica is never pointed at the real node directly: `scripts/txblock-proxy.py`, a
stdlib-only Python reverse proxy, sits between them on an isolated Docker network and forwards
everything except `POST /transactions`, which it refuses with a `400` and logs loudly instead
of relaying to the upstream node.

A run's containers, network and (on success, without `--keep`) run directory are cleaned up
automatically; container logs are always saved to the run directory before removal.

## State persistence

Rebuilding state by replaying the whole transaction archive from genesis on every
restart gets slower as the archive grows. Instead, confirmed AVL+ state is
checkpointed to disk as sync progresses, so a normal restart can resume from the last
checkpoint instead of replaying anything.

- **What's persisted, and where.** `paideia.stateDir` (default `state`, bind-mounted
  as `./state:/opt/docker/state`, override with `STATE_DIR`) holds `state.json`
  (registries: DAOs, config digests, proposals, staking state/snapshot digests, plus
  the checkpoint height) and `boxes/<daoKey>/<contractSignatureHash>.json` (each
  contract instance's confirmed unspent boxes). Both are written atomically by
  `Paideia.persistState`, called after `Paideia.commit()` drains that block's queued
  AVL+ mutations into the versioned on-disk stores (`daoconfigs/`, `stakingStates/`,
  `proposals/` — unchanged from before persistence existed, just now checkpointed
  alongside `state/` instead of being the only on-disk record).
- **When a checkpoint is taken.** Once per confirmed block while sync is within
  `virtualMempoolHeight + 1` of the node tip (i.e. once caught up); every
  `paideia.checkpointInterval` blocks (default 100) while still catching up from far
  behind, so a large catch-up isn't slowed down by checkpointing every single block.
  Also once at the end of a full archive replay, so a fresh replay always leaves a
  checkpoint behind for the next restart. Mempool events, rollbacks and generated
  (not-yet-confirmed) transactions never trigger a checkpoint — that state is
  ephemeral by design.
- **Restore vs. replay decision, on every start.** Before anything else,
  `PaideiaStateActor` tries `Paideia.restoreState(stateDir)`, which rebuilds every
  registry and verifies every digest against what's actually reopened from disk.
  - Success (`Some(height)`): the genesis seeding step and archive replay are both
    skipped entirely; sync resumes at `height + 1` (a checkpoint is only ever taken
    after the block at `height` has been fully handled, so that's the next block to
    process). The replica log line `Restored state at height <h> from <dir>, skipping
    archive replay` confirms this happened.
  - Failure (no `state.json`, or a digest mismatch — a stale or partial checkpoint):
    logged at `warn` with the reason, then any partially-restored registries/stores are
    cleared and `daoconfigs/`, `proposals/`, `stakingStates/` are wiped (a partial
    checkpoint must never be reused), and the service falls back to exactly the
    original boot path — seed genesis config, then `syncFromArchive` from
    `paideia.syncStart`.
- **Forcing a full replay.** Delete the `state/` directory (or point `STATE_DIR` at an
  empty one) and restart. `daoconfigs/`, `stakingStates/` and `proposals/` don't need
  to be touched — they're wiped automatically as part of the no-checkpoint fallback
  path above.
- **Verifying the restore path.** `scripts/replay-regression.sh --restart` exercises
  this end to end: restart the replica after a normal sync, confirm the "Restored
  state at height" log line appears (i.e. it didn't silently fall back to a replay),
  and diff its API responses against prod again.

## Health and readiness

- `GET /health` — liveness. Always `200` while the process is up; the body carries
  `syncing`, `currentHeight`, `nodeHeight`, `lag`, `restoredFromCheckpoint` and
  `checkpointHeight` (the last two describe the boot-time restore/replay decision
  above) for inspection.
- `GET /ready` — readiness. `503` while the service is syncing (every other endpoint rejects
  requests with a "currently syncing" error during that time), `200` once caught up. Point
  load balancers, docker health checks and uptime monitors here, not at `/health`.

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
`transaction_archive/`, `errors/` and `state/` directories, so it survives image
updates. To roll back, point `image:` at a previous `sha-` tag and `docker compose up
-d` again.

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
(cd ../paideia-sdk && sbt 'set version := "1.0.0-rc5-SNAPSHOT"' publishLocal)
sbt compile
sbt run
```

The service listens on port 9000 (mapped to 9124 by compose) and exposes `/health`.
