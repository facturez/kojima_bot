# Multi-Guild Kojima Bot Design

## Goal

Convert the existing Java 17/JDA 5/SQLite Kojima Bot into a multi-guild bot without rewriting the project or losing the existing message archive. Every guild is an isolated tenant: a command or event from one guild must never read, count, change, delete, or use another guild's data or configuration.

## Architecture

The root Maven module remains the deployable application. The historical nested `kojima_bot/` copy is not part of the root build and is left unchanged.

Database responsibilities are separated into three focused components:

- `DatabaseMigrator` owns transactional, versioned, non-destructive schema upgrades.
- `GuildConfigRepository` owns typed guild, daily-message, call-command, allowed-role, and scheduler-state persistence.
- `MessageRepository` remains responsible only for the message archive and retention.

No ORM, migration framework, generic key/value settings table, or database replacement is introduced. All repositories continue to use JDBC and `BOT_DB_PATH`.

## Database Schema

`schema_version` stores the current schema version. The multi-guild migration creates the following typed tables and indexes without dropping or rebuilding `messages`:

- `guilds(guild_id TEXT PRIMARY KEY, guild_name TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1, timezone TEXT NOT NULL DEFAULT 'Europe/Moscow', archive_enabled INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)`
- `daily_message_settings(guild_id TEXT PRIMARY KEY, enabled INTEGER NOT NULL DEFAULT 0, channel_id TEXT, timezone TEXT NOT NULL DEFAULT 'Europe/Moscow', message_prefix TEXT NOT NULL, base_date TEXT NOT NULL, base_day_number INTEGER NOT NULL, FOREIGN KEY(guild_id) REFERENCES guilds(guild_id))`
- `daily_message_state(guild_id TEXT PRIMARY KEY, last_sent_date TEXT, FOREIGN KEY(guild_id) REFERENCES guilds(guild_id))`
- `call_settings(guild_id TEXT PRIMARY KEY, enabled INTEGER NOT NULL DEFAULT 0, message_text TEXT NOT NULL, repeat_count INTEGER NOT NULL, FOREIGN KEY(guild_id) REFERENCES guilds(guild_id))`
- `call_allowed_roles(guild_id TEXT NOT NULL, role_id TEXT NOT NULL, PRIMARY KEY(guild_id, role_id), FOREIGN KEY(guild_id) REFERENCES guilds(guild_id))`
- `migration_markers(marker TEXT PRIMARY KEY, completed_at TEXT NOT NULL)`

The existing `scheduler_state` table is retained for backward compatibility but is no longer used by production scheduling. An index on `messages(guild_id, channel_id, created_at)` supports tenant-scoped archive reads. SQLite foreign keys are enabled on every connection.

## Guild Lifecycle and Defaults

`GuildLifecycleListener` handles `GuildJoinEvent` and `GuildLeaveEvent`.

On join, it upserts the guild name, sets `active=true`, and creates missing daily and call rows with disabled defaults. It does not overwrite existing configuration and does not send an unsolicited message. On leave, it only sets `active=false`; archived data and configuration remain available if the bot returns.

New guild defaults are:

- timezone `Europe/Moscow`
- `archive_enabled=false`
- daily message disabled
- call command disabled

At startup, currently connected guilds are also upserted so installations upgraded while the bot remains present receive configuration rows even though no join event fires.

## Legacy Bootstrap

When `LEGACY_GUILD_ID` is nonblank, a transactional bootstrap runs once for that guild. A guild-specific marker prevents later restarts from overwriting administrator changes.

The bootstrap creates or activates the guild and migrates the legacy daily channel, timezone, prefix, base date, and base day number plus call message, repeat count, and allowed role IDs. It enables the archive to preserve the existing installation's behavior. Daily and call are enabled only when their legacy configuration is usable: daily requires a real channel ID and call requires nonblank non-placeholder text. No real guild ID is committed.

If `LEGACY_GUILD_ID` is absent, no existing guild is guessed. Connected guilds receive safe disabled defaults.

## Archive Isolation

DMs are never archived. A guild message is stored only when that guild exists and has `archive_enabled=true`.

Every server-context archive API explicitly accepts `guildId`:

- save a guild message
- delete one or multiple guild messages
- count guild messages
- count a guild author's messages
- read recent messages for a guild and channel

SQL includes `guild_id = ?` even where Discord IDs are globally unique. `/stats`, `!stats`, `/last`, and `!last` reject DM use and operate only on the current guild. Existing Manage Messages checks for archive reads remain in place. Discord message-delete events pass their guild ID to deletion APIs. Retention cleanup is a process-wide maintenance operation applying the same age policy to all rows; it neither returns tenant data nor runs on behalf of a guild command.

## Typed Configuration Commands

`/setup` is guild-only and requires `MANAGE_SERVER` or `ADMINISTRATOR`. Discord's default command permission is `MANAGE_SERVER`, and the handler independently rechecks permissions.

It contains three subcommands whose options are optional so a caller can change one field without resetting others. A request with no supplied change is rejected.

### `/setup daily`

Options: `enabled` (boolean), `channel` (guild message-capable channel), `timezone` (string IANA zone ID), `message_prefix` (string), `base_date` (`YYYY-MM-DD` string), and `base_day_number` (integer). The handler validates the zone, date, nonblank prefix, numeric range, and that the selected channel belongs to the current guild. Enabling requires a configured channel.

### `/setup call`

Options: `enabled` (boolean), `message` (string), `repeat_count` (integer with Discord and server-side range 1–10), `allowed_role` (role), and `role_action` (choice: `add`, `remove`, or `clear`). Role membership is scoped by the composite `(guild_id, role_id)` key. `add` and `remove` require a role; `clear` rejects a role. If a role is supplied without an action, `add` is used. Enabling requires nonblank configured text.

### `/setup archive`

Option: `enabled` (boolean). It is required because this subcommand has only one setting. Disabling stops future collection but does not destroy existing archived messages.

Successful changes use transactional partial-update methods and return an ephemeral summary. Daily changes notify the scheduler to reschedule only that guild.

`/config` is guild-only and replies ephemerally with the current archive, daily, and call settings plus allowed roles. It performs no mutation and is available to guild members because the values are operational configuration, not secrets.

## Call Command

`/зов` and `!зов` load the current guild's call settings and allowed roles. They reject DMs, disabled configuration, blank text, and unauthorized callers. Authorization is granted to an administrator or a member holding an allowed role from the same guild. The outgoing message uses the configured repeat count and retains the existing controlled `@everyone` mention behavior.

## Multi-Guild Scheduler

One `ScheduledExecutorService` owns all daily jobs. `MultiGuildDailyMessageScheduler` loads active enabled configurations on startup and maintains one scheduled future per guild without creating a thread per guild.

For each guild it calculates the next local midnight from that guild's timezone. At startup it performs the existing bounded catch-up behavior using `daily_message_state(guild_id, last_sent_date)`. A successful Discord send records the local date for that guild; failures do not advance state and use bounded retries. An in-flight guard prevents duplicate concurrent sends for one guild. The configured channel is additionally checked to belong to the configured guild before sending.

After a successful `/setup daily`, the scheduler cancels and recalculates only that guild's future. Disabling daily removes its scheduled future. Guild leave also unschedules the guild, while a later join restores its saved configuration.

## Moderation and Error Handling

Existing `/deport`, `/magadan`, `/kpz`, and `/clear` permission, hierarchy, bot-permission, audit-reason, and Discord-error behavior remains unchanged. Refactoring shared handlers must not widen authorization.

Invalid configuration input returns a clear ephemeral response and performs no write. Repository failures are logged and produce generic user-facing errors without SQL details. Schema migration failure aborts startup rather than running with a partially upgraded database.

## Testing

Implementation follows red-green-refactor. Tests cover:

- non-destructive, repeatable migration and expected indexes/tables;
- default guild creation, leave/reactivation, and one-time legacy bootstrap;
- partial typed updates and validation;
- cross-guild isolation for save, count, recent reads, deletion, call roles, and daily state;
- archive opt-in and explicit DM exclusion;
- guild-scoped `/stats`, `/last`, `!stats`, and `!last`;
- `/setup` permissions, option routing, partial updates, and ephemeral responses;
- `/config` guild-only ephemeral output;
- per-guild scheduler timing, catch-up, retry, channel ownership, refresh, and state;
- regression coverage for all moderation commands.

The final verification is `mvn clean test` and `mvn clean package` from the repository root.
