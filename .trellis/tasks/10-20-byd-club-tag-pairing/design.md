# Technical Design

## Architecture and Boundary

```text
BydDynastyProperties.defaultClubId/defaultClubTitle
                │
                ▼
GET state ──► clubTagOptions[{clubId, clubTitle}]
                │
                ▼
BydDynastyPanel.vue single selector
                │  exact pair
                ▼
POST push-jobs {clubId, clubTitle}
                │
                ▼
BydDynastyPushService pair resolver
                │
                ├─ job payload: clubId + clubTitle
                └─ on success: project byd_dynasty_club_* pair
                              │
                              ▼
                   Agent pair resolver → MMKV draft
```

The web request keeps the existing two-field transport contract for compatibility, but the pair is atomic at the service boundary. The frontend does not derive, concatenate, or independently fallback either field.

## Contracts

### GET `/api/projects/{id}/byd-dynasty/state`

Add:

```json
{
  "clubTagOptions": [
    {"clubId": "220", "clubTitle": "四川迪友会"}
  ],
  "clubId": "220",
  "clubTitle": "四川迪友会"
}
```

- Options are complete pairs only.
- Sources are the configured default pair and the project history pair, deduplicated by exact pair.
- `clubId` / `clubTitle` remain for compatibility and represent the initial selection.

### POST `/api/projects/{id}/byd-dynasty/push-jobs`

Keep `{clubId?, clubTitle?}`:

- both blank/missing → use complete project pair, else complete default pair, else no tag.
- both present → must exactly match an available pair.
- exactly one present → Chinese 400.
- no job/update side effect occurs on validation failure.

### Job payload

Keep top-level:

```json
{"clubId": "220", "clubTitle": "四川迪友会"}
```

or both null for no tag.

### Success persistence

On Agent success, parse the job v2 payload and set `byd_dynasty_club_id` and `byd_dynasty_club_title` from the same payload pair. If the payload is legacy v1 and has no club keys, leave project history unchanged.

## Server Design

Introduce a small private value object in `BydDynastyPushService`:

```java
record ClubTag(String clubId, String clubTitle) {}
```

Centralize:

- `normalizeClubTag(clubId, clubTitle)` — trims and rejects partial pairs.
- `availableClubTags(project)` — complete default + complete project pair, deduplicated.
- `resolveClubTag(req, project)` — atomic request/project/default precedence and exact-option validation.

Do not use `firstNonBlank` independently for these fields.

`BydDynastyProperties` may expose a `defaultClubTag()` helper returning null unless both defaults are nonblank. This keeps the config keys backward compatible and avoids a new deployment variable.

## Frontend Design

`BydDynastyPanel.vue`:

- Replace two `el-input`s with one `el-select` labeled “车友会标签”.
- Empty value displays “不使用”.
- Bind selected `clubId`; resolve the selected complete option from `clubTagOptions`.
- Submit both fields from the same selected option.
- Preserve selection during polling; refresh options and selected pair from state.
- Show a clear disabled/empty state when no complete options are available.

No hardcoded IDs or names in Vue source.

## Agent Design

Add an atomic resolver in `byd_local_agent.py`:

- payload has two nonblank fields → use the payload pair.
- payload has both blank/absent → use local pair only if complete, else no tag.
- payload has exactly one nonblank field → `AgentError`.
- local config has exactly one nonblank field → `AgentError` when fallback is needed, rather than mixing fields.

Legacy v1 payloads that omit both fields remain compatible with a complete local default pair.

## Compatibility and Rollout

- No DB migration.
- Existing complete project pairs remain valid and become options.
- Incomplete historical project/default pairs are ignored rather than auto-completed.
- Deployment rebuilds both images because Java and Vue code change.
- Rollback is the previous Git commit plus `./deploy.sh --no-pull`; no data deletion or volume changes.

## Risks

- A project whose existing pair is not present in the configured default remains selectable because it is included from project history.
- If all historical/default pairs are incomplete, users can still choose “不使用”; this preserves push availability instead of blocking on configuration.
- Agent source changes are tested but are not shipped as a new Windows package in this task.

## Validation Strategy

- Java unit tests cover state options, pair resolution, partial pair rejection, unknown pair rejection, precedence, and success persistence.
- Agent pytest covers payload pair, config fallback, partial payload, and partial config.
- Frontend production build validates the single-selector integration.
- Remote deployment verifies images build and containers become healthy.