# Implementation Plan

## 1. Server Pair Model and State

- [x] Add an atomic club-tag resolver/value object in `BydDynastyPushService`.
- [x] Add a complete-pair helper to `BydDynastyProperties`.
- [x] Return `clubTagOptions` from `state` and keep `clubId` / `clubTitle` compatible.
- [x] Reject partial and unknown request pairs with Chinese 400 messages.

## 2. Server Persistence

- [x] On successful v2 job completion, write the payload's pair into `byd_dynasty_club_id` / `byd_dynasty_club_title`.
- [x] Keep failed jobs from changing project tag history.
- [x] Keep legacy v1 jobs from clearing history when club keys are absent.

## 3. Frontend

- [x] Replace the two inputs with one “车友会标签” `el-select`.
- [x] Populate options from `state.clubTagOptions`; do not hardcode tag values.
- [x] Submit only the exact selected pair; empty means no tag.
- [x] Preserve current polling/reload behavior and editor/viewer permissions.

## 4. Agent Defense

- [x] Add an atomic payload/config pair resolver in `agent/byd-dynasty/byd_local_agent.py`.
- [x] Reject partial payload pairs and partial local defaults with user-facing Chinese errors.
- [x] Preserve legacy v1 fallback to complete local configuration.

## 5. Tests

- [x] Extend `BydDynastyPushServiceTest` for options, precedence, partial/unknown rejection, no-side-effect failure, and success persistence.
- [x] Extend `agent/byd-dynasty/tests/test_byd_local_agent.py` for valid payload pair, config fallback, partial payload, partial config, and v1 compatibility.
- [x] Run:
  - `mvn test`
  - `Set-Location frontend; npm run build`
  - `Set-Location agent/byd-dynasty; python -m pytest -q`
  - `git diff --check`

## 6. Review and Commit

- [ ] Review the full diff and confirm `gzh`/七牛/BYD media paths are unchanged.
- [ ] Stage only task files and the unrelated clean task metadata changes required for this task; never `git add -A`.
- [ ] Commit with message: `fix(10-20): 统一 BYD 车友会标签成对处理`.
- [ ] Push `HEAD` to `origin/feature/byd-dynasty-miniapp-push` without force.

## 7. Remote Image Build and Deployment

- [ ] SSH preflight: verify alias, branch, tracked working tree, current commit, compose config, and disk.
- [ ] Record the pre-deploy commit for rollback.
- [ ] Fetch and `git pull --ff-only` the feature branch on `sparkora-dev`.
- [ ] Run `./deploy.sh --no-pull` to rebuild backend/frontend images and replace containers.
- [ ] Verify `docker compose ps`, backend healthy, frontend HTTP 200, backend `/api/auth/me` HTTP 401.
- [ ] Check relevant logs for startup/migration errors.
- [ ] Report branch, deployed commit, image build result, container status, and health checks.

## Risk / Rollback Points

- Stop before commit if any required test/build fails.
- Stop before deployment if remote branch is not fast-forwardable or has conflicting tracked changes.
- If deployment fails due to this commit, restore the recorded pre-deploy commit and run `./deploy.sh --no-pull`; do not remove data volumes.
- Do not deploy if the working tree contains uncommitted runtime code changes.