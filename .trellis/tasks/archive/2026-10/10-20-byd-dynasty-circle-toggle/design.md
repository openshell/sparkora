# Technical Design

## 1. Architecture and Boundaries

### Frontend

`BydDynastyPanel.vue` owns a boolean `includeCircle` state:

- Default from `state.circleConfigured`.
- Toggle is disabled when no complete fixed circle is configured.
- Polling preserves a user-touched choice if the configuration remains valid.
- Create request sends only `{ "useCircle": boolean }`; it does not send client-selected IDs.

### Server

`BydDynastyPushService` owns the only fixed circle, sourced from `BydDynastyProperties.defaultClubId/defaultClubTitle`.

- No multi-option catalog is generated.
- No project historical pair is admitted as a second business option.
- Project `byd_dynasty_club_*` fields remain last-success audit fields.
- Legacy request fields remain accepted only for compatibility and must match the configured fixed pair when used.

### Agent

The payload v2 contract remains unchanged:

```json
{"clubId": "220", "clubTitle": "四川迪友会"}
```

or, when the user disables the circle:

```json
{"clubId": null, "clubTitle": null}
```

The Agent still validates that the pair is complete and writes these values into the MMKV draft. Only user-facing error text changes to “圈子”.

## 2. API Contracts

### GET `/api/projects/{id}/byd-dynasty/state`

New authoritative fields:

```json
{
  "circleConfigured": true,
  "circleId": "220",
  "circleTitle": "四川迪友会",
  "circleDisabledReason": null
}
```

Missing configuration:

```json
{
  "circleConfigured": false,
  "circleId": null,
  "circleTitle": null,
  "circleDisabledReason": "服务端未配置完整的 BYD 小程序圈子 ID 和圈子名称"
}
```

Compatibility fields retained:

- `clubId` / `clubTitle`: same configured pair.
- `clubTagOptions`: zero or one configured pair. New frontend ignores this field.

### POST `/api/projects/{id}/byd-dynasty/push-jobs`

New request:

```json
{"useCircle": true}
```

```json
{"useCircle": false}
```

Resolution priority:

1. `useCircle=true`: require a complete configured pair and use it. If legacy pair fields are also present and conflict, reject.
2. `useCircle=false`: require no non-empty legacy pair fields and create a no-circle payload.
3. `useCircle` absent: keep legacy compatibility using `useClubTag` / pair semantics, but any supplied pair must match the configured fixed pair.
4. No explicit selection and no legacy fields: use the configured fixed pair when present, otherwise no circle.

## 3. Data Flow

```text
server env (single circle)
  -> BydDynastyProperties
  -> state.circleConfigured + circleTitle
  -> frontend includeCircle switch
  -> push-jobs {useCircle}
  -> service chooses configured pair or null pair
  -> payload v2 clubId/clubTitle
  -> Agent validates and writes MMKV draft
  -> successful job writes last pair back to project audit columns
```

## 4. Validation Matrix

| Condition | Result |
| --- | --- |
| `useCircle=true`, complete config | Job uses fixed pair |
| `useCircle=true`, incomplete config | Chinese 400, no job |
| `useCircle=false` | Payload contains both fields as null |
| `useCircle=false` plus non-empty legacy pair | Chinese 400 |
| `useCircle=true` plus legacy pair different from config | Chinese 400 |
| payload has only one club field | Agent rejects before MMKV |
| payload has both fields null | Agent writes empty circle fields, no local fallback |
| payload missing both fields | Legacy v1 fallback to complete local config |

## 5. Compatibility and Migration

- No database migration is needed.
- Keep environment variable names `BYD_DYNASTY_CLUB_ID` / `BYD_DYNASTY_CLUB_TITLE` because they mirror the BYD draft protocol.
- Keep payload field names and Agent draft fields unchanged to avoid a coordinated payload-version migration.
- Keep server acceptance of legacy `useClubTag` requests for one release window.
- Existing project history remains stored but is not used as an option catalog.

## 6. Operational and Rollback Notes

- Set the fixed circle in server `.env` without printing the file.
- Recreate the backend container so `env_file` changes are loaded.
- Rollback code to the pre-deployment commit if health checks fail; keep project and media data untouched.
- If the fixed circle config is absent, deployment remains healthy but the UI must clearly allow no-circle pushes while disabling circle inclusion.
