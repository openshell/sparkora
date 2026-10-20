# Implementation Plan

## 1. Backend

- [ ] Update `BydPushJobCreateRequest` with explicit `useCircle` while retaining legacy fields.
- [ ] Change `BydDynastyPushService.state` to expose fixed-circle availability and remove multi-option behavior from the new path.
- [ ] Resolve circle inclusion exclusively from the configured fixed pair; reject conflicting legacy pairs.
- [ ] Keep payload v2 `clubId` / `clubTitle` fields, using a null pair for no-circle jobs.
- [ ] Preserve successful-job project write-back and failure non-mutation semantics.
- [ ] Update user-facing terminology from 车友会标签 to 圈子.
- [ ] Update service tests for fixed-circle state, `useCircle` true/false, missing config, legacy compatibility, and payload/project write-back.

## 2. Frontend

- [ ] Replace the `clubTagOptions` dropdown with one `携带圈子` switch and configured circle name.
- [ ] Default switch on when configured; disable and warn when not configured.
- [ ] Preserve touched choice across polling and force off if config becomes invalid.
- [ ] Submit only `useCircle`, without client-selected IDs.
- [ ] Remove user-visible 车友会标签 wording from the BYD panel.

## 3. Agent and Documentation

- [ ] Keep payload/MMKV club pair contract unchanged.
- [ ] Update Agent validation errors and tests from 车友会标签 to 圈子.
- [ ] Update `.env.example`, Agent README, channel spec, backend BYD spec, and frontend spec.
- [ ] Document that `clubId/clubTitle` are protocol fields and the user-facing object is a 圈子.

## 4. Local Validation

Run with JDK 21:

```powershell
$env:JAVA_HOME='C:\Program Files (x86)\Common Files\JDK\jdk-21'
mvn test
Set-Location agent/byd-dynasty
python -m pytest -q
Set-Location ../..
Set-Location frontend
npm run build
Set-Location ..
git diff --check
```

Also verify:

- No stale user-facing BYD `车友会标签` text.
- No multi-option dropdown remains in `BydDynastyPanel.vue`.
- No secrets are printed or committed.

## 5. Commit and Push

- [ ] Review `git diff` and ensure unrelated dirty files remain untouched.
- [ ] Commit product changes and task artifact changes separately if appropriate.
- [ ] Push the current feature branch without force.

## 6. Development Deployment

- [ ] SSH preflight: branch, commit, tracked status, compose config, disk.
- [ ] Record pre-deployment commit.
- [ ] Securely confirm/update these `.env` entries without printing the file:
  - `BYD_DYNASTY_CLUB_ID=220`
  - `BYD_DYNASTY_CLUB_TITLE=四川迪友会`
- [ ] Fast-forward pull on the remote feature branch.
- [ ] Run `./deploy.sh --no-pull` to rebuild images.
- [ ] Verify backend healthy, frontend HTTP 200, `/api/auth/me` 401.
- [ ] Verify container environment variables are non-empty without printing secrets.
- [ ] Confirm running image corresponds to the deployed business commit.
- [ ] Inspect backend/frontend logs for startup or runtime errors.

## 7. Risk and Rollback

- Main risk: a stale open frontend tab sends legacy fields; compatibility must keep it valid only against the configured pair.
- Main operational risk: `.env` changes are not applied by a simple restart; use container recreation.
- If deployment fails or becomes unhealthy, stop, preserve logs, and redeploy the recorded pre-deployment commit using the documented rollback procedure.
