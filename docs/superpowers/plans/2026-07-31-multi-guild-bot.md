# Multi-Guild Kojima Bot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Kojima Bot a safe multi-guild Discord bot with persistent, isolated guild configuration, opt-in archives, per-guild daily scheduling, and typed `/setup` plus `/config` commands.

**Architecture:** A transactional `DatabaseMigrator` upgrades SQLite without touching archived messages, `GuildConfigRepository` owns typed tenant configuration, and `MessageRepository` owns guild-scoped archive operations. One `MultiGuildDailyMessageScheduler` schedules all enabled guilds, while Discord listeners and command handlers resolve the current guild before every configuration or archive operation.

**Tech Stack:** Java 17, Maven, JDA 5.0.0-beta.24, SQLite JDBC 3.46.1.3, JUnit Jupiter 5.10.2

## Global Constraints

- Preserve SQLite, JDBC, Java 17, Maven, JDA 5, JUnit, `BOT_DB_PATH`, and every row in the existing `messages` table.
- Never use a guild command or event to access another guild's archive, configuration, roles, channels, or scheduler state.
- Do not archive DMs; new guilds default to archive, daily messages, and call disabled.
- Do not add an ORM, migration framework, generic settings table, or production Discord IDs in source.
- Keep existing moderation authorization, hierarchy checks, bot-permission checks, and error handling unchanged.
- Implement production behavior only after observing its focused test fail.

---

### Task 1: Versioned, Non-Destructive Database Migration

**Files:**
- Create: `src/main/java/org/example/db/DatabaseConnectionFactory.java`
- Create: `src/main/java/org/example/db/DatabaseMigrator.java`
- Create: `src/test/java/org/example/db/DatabaseMigratorTest.java`
- Modify: `src/main/java/org/example/db/MessageRepository.java`

**Interfaces:**
- Produces: `DatabaseConnectionFactory(String databasePath)`, `Connection open()`, `DatabaseMigrator(DatabaseConnectionFactory)`, and `void migrate()`.
- Produces schema version 2 with `guilds`, `daily_message_settings`, `daily_message_state`, `call_settings`, `call_allowed_roles`, `migration_markers`, and index `idx_messages_guild_channel_created`.
- `MessageRepository` consumes `DatabaseConnectionFactory` and no longer owns DDL.

- [ ] **Step 1: Write failing migration tests**

Create tests that first build the legacy `messages` and `scheduler_state` tables, insert an archive row, call `migrate()`, and assert that the row remains; schema version is `2`; every typed table and index exists; `PRAGMA foreign_keys` is `1`; and a second `migrate()` succeeds without changing data.

- [ ] **Step 2: Verify RED**

Run: `mvn -Dtest=DatabaseMigratorTest test`

Expected: compilation fails because `DatabaseMigrator` and `DatabaseConnectionFactory` do not exist.

- [ ] **Step 3: Implement migration infrastructure**

`DatabaseConnectionFactory.open()` must create `jdbc:sqlite:<path>` connections and execute `PRAGMA foreign_keys = ON`. `DatabaseMigrator.migrate()` must open one connection, disable auto-commit, create `schema_version` if absent, infer legacy version `1` when `messages` exists, create the existing messages schema when absent, apply version 2 DDL, write version `2`, commit, and rollback on any `SQLException` before throwing `IllegalStateException`.

- [ ] **Step 4: Move MessageRepository to the shared factory**

Retain public constructors but delegate connection creation and initialization to the factory and migrator. Do not change archive method signatures yet.

- [ ] **Step 5: Verify GREEN and regression suite**

Run: `mvn -Dtest=DatabaseMigratorTest,MessageRepositoryTest test`

Expected: all selected tests pass and the legacy archive row remains.

- [ ] **Step 6: Commit**

Run: `git add src/main/java/org/example/db src/test/java/org/example/db && git commit -m "feat: add versioned multi-guild database migration"`

### Task 2: Typed Guild Configuration Repository and Legacy Bootstrap

**Files:**
- Create: `src/main/java/org/example/db/GuildConfig.java`
- Create: `src/main/java/org/example/db/DailyMessageSettings.java`
- Create: `src/main/java/org/example/db/CallSettings.java`
- Create: `src/main/java/org/example/db/DailySettingsPatch.java`
- Create: `src/main/java/org/example/db/CallSettingsPatch.java`
- Create: `src/main/java/org/example/db/LegacyGuildConfig.java`
- Create: `src/main/java/org/example/db/GuildConfigRepository.java`
- Create: `src/test/java/org/example/db/GuildConfigRepositoryTest.java`

**Interfaces:**
- Produces records with immutable typed configuration and `Optional` patch fields so absent values remain unchanged.
- Produces `void activateGuild(String guildId, String guildName)`, `void deactivateGuild(String guildId)`, `Optional<GuildConfig> findGuild(String guildId)`, `GuildConfig requireConfig(String guildId)`, `List<DailyMessageSettings> findActiveDailySettings()`, `void updateDaily(String guildId, DailySettingsPatch patch)`, `void updateCall(String guildId, CallSettingsPatch patch)`, `void setArchiveEnabled(String guildId, boolean enabled)`, `void addAllowedRole(String guildId, String roleId)`, `void removeAllowedRole(String guildId, String roleId)`, `void clearAllowedRoles(String guildId)`, `Optional<LocalDate> getLastDailyMessageDate(String guildId)`, `void setLastDailyMessageDate(String guildId, LocalDate date)`, and `boolean bootstrapLegacy(String guildId, String guildName, LegacyGuildConfig config)`.

- [ ] **Step 1: Write failing default/isolation tests**

Test that activation creates disabled defaults, a leave preserves settings while setting inactive, reactivation preserves settings, daily state differs by guild, and a role added for guild A is absent from guild B.

- [ ] **Step 2: Verify RED**

Run: `mvn -Dtest=GuildConfigRepositoryTest test`

Expected: compilation fails because typed configuration classes do not exist.

- [ ] **Step 3: Implement records and repository reads/writes**

Use transactions for activation and each multi-table update. Every read or write SQL statement must bind `guild_id`; allowed roles use both `guild_id` and `role_id`. `requireConfig` throws for an unknown guild instead of silently creating one during a command.

- [ ] **Step 4: Write failing partial-update/bootstrap tests**

Test that changing only timezone keeps channel/prefix/base fields; changing repeat count keeps message/enabled/roles; legacy bootstrap enables archive and usable legacy functions; and a second bootstrap returns false without overwriting administrator changes.

- [ ] **Step 5: Verify RED, implement, and verify GREEN**

Run before implementation: `mvn -Dtest=GuildConfigRepositoryTest test`

Expected: the new partial-update and bootstrap assertions fail.

Implement field-selective SQL inside transactions and marker `legacy-config-v1:<guildId>`, then rerun the same command and expect all tests to pass.

- [ ] **Step 6: Commit**

Run: `git add src/main/java/org/example/db src/test/java/org/example/db/GuildConfigRepositoryTest.java && git commit -m "feat: persist isolated guild configuration"`

### Task 3: Guild-Scoped Message Archive

**Files:**
- Modify: `src/main/java/org/example/db/MessageRepository.java`
- Modify: `src/test/java/org/example/db/MessageRepositoryTest.java`

**Interfaces:**
- Replaces global archive APIs with `saveGuildMessage(String guildId, Message message)`, `deleteMessage(String guildId, String messageId)`, `deleteMessages(String guildId, Collection<String> messageIds)`, `countMessages(String guildId)`, `countMessagesByAuthor(String guildId, String authorId)`, and `findRecentMessages(String guildId, String channelId, int limit)`.
- Package-visible test helper `saveStoredMessage` keeps `guildId` mandatory.

- [ ] **Step 1: Write failing tenant-isolation tests**

Insert messages using identical author/channel patterns across guild A and B. Assert each count and recent read sees only its guild, deletion initiated for A cannot delete B's row, and every stored test message has a nonnull guild ID.

- [ ] **Step 2: Verify RED**

Run: `mvn -Dtest=MessageRepositoryTest test`

Expected: compilation fails against the desired guild-aware signatures.

- [ ] **Step 3: Implement explicit guild SQL**

Add `guild_id = ?` to every command-context `SELECT` and `DELETE`. Bind guild before author/channel/message identifiers. Keep global age-based retention cleanup as lifecycle maintenance.

- [ ] **Step 4: Verify GREEN**

Run: `mvn -Dtest=MessageRepositoryTest test`

Expected: all archive tests pass, including cross-guild deletion protection.

- [ ] **Step 5: Commit**

Run: `git add src/main/java/org/example/db/MessageRepository.java src/test/java/org/example/db/MessageRepositoryTest.java && git commit -m "feat: scope message archive by guild"`

### Task 4: Guild Lifecycle and Archive Opt-In Listener

**Files:**
- Create: `src/main/java/org/example/bot/GuildLifecycleListener.java`
- Create: `src/test/java/org/example/bot/GuildLifecycleListenerTest.java`
- Modify: `src/main/java/org/example/bot/MessageListener.java`
- Modify: `src/test/java/org/example/bot/MessageListenerTest.java`

**Interfaces:**
- `GuildLifecycleListener(GuildConfigRepository, Consumer<String> guildActivated, Consumer<String> guildDeactivated)` handles join/leave.
- `MessageListener(MessageRepository, GuildConfigRepository, CommandHandler, SlashCommandHandler)` archives only opted-in guild messages and delegates commands.

- [ ] **Step 1: Write failing listener tests**

Test that DMs, bots, webhooks, and archive-disabled guild messages are not saved; archive-enabled guild messages are saved with the event guild ID; delete and bulk-delete events include the event guild ID; join activates without sending; and leave deactivates without deleting.

- [ ] **Step 2: Verify RED**

Run: `mvn -Dtest=MessageListenerTest,GuildLifecycleListenerTest test`

Expected: compilation or assertions fail because listeners are not guild-aware.

- [ ] **Step 3: Implement listener behavior**

Check `event.isFromGuild()` before all archive access. Query `archive_enabled` for the exact guild before saving. Ignore DM delete events. Keep command dispatch for guild messages regardless of archive setting.

- [ ] **Step 4: Verify GREEN**

Run: `mvn -Dtest=MessageListenerTest,GuildLifecycleListenerTest test`

Expected: all listener tests pass.

- [ ] **Step 5: Commit**

Run: `git add src/main/java/org/example/bot src/test/java/org/example/bot && git commit -m "feat: add safe guild lifecycle and archive opt-in"`

### Task 5: Guild-Scoped Prefix and Slash Commands

**Files:**
- Modify: `src/main/java/org/example/bot/CommandAuthorization.java`
- Modify: `src/main/java/org/example/bot/CommandHandler.java`
- Modify: `src/main/java/org/example/bot/SlashCommandHandler.java`
- Modify: `src/test/java/org/example/bot/CommandAuthorizationTest.java`
- Modify: `src/test/java/org/example/bot/CommandHandlerTest.java`
- Modify: `src/test/java/org/example/bot/SlashCommandHandlerTest.java`

**Interfaces:**
- Both handlers consume `GuildConfigRepository` in addition to `MessageRepository`.
- Stats/last call only guild-aware repository signatures.
- Call authorization accepts administrator plus current member role IDs and current guild allowed-role IDs.

- [ ] **Step 1: Write failing stats/last isolation tests**

For both handler styles, assert DM rejection and capture the guild ID passed to counts and recent reads. Preserve the existing Manage Messages denial tests for `last`.

- [ ] **Step 2: Verify RED and implement scoped archive calls**

Run before implementation: `mvn -Dtest=CommandHandlerTest,SlashCommandHandlerTest test`

Expected: new assertions fail because handlers use global APIs.

Pass `message.getGuild().getId()` or `event.getGuild().getId()` to repository calls, reject non-guild stats/last, rerun, and expect the focused tests to pass.

- [ ] **Step 3: Write failing call-configuration tests**

Assert disabled call rejection, configured text/repeat count use, same-guild allowed role success, foreign-guild role denial, administrator success, and DM rejection for both prefix and slash commands.

- [ ] **Step 4: Verify RED and implement call configuration**

Run the focused handler tests and expect call tests to fail against `AdminCommandConfig`. Load `CallSettings` and roles by current guild, then rerun and expect all handler tests to pass.

- [ ] **Step 5: Commit**

Run: `git add src/main/java/org/example/bot src/test/java/org/example/bot && git commit -m "feat: isolate archive and call commands by guild"`

### Task 6: Typed `/setup` and Ephemeral `/config`

**Files:**
- Create: `src/main/java/org/example/bot/SetupCommandHandler.java`
- Create: `src/main/java/org/example/bot/SetupValidation.java`
- Create: `src/test/java/org/example/bot/SetupCommandHandlerTest.java`
- Create: `src/test/java/org/example/bot/SetupValidationTest.java`
- Modify: `src/main/java/org/example/bot/SlashCommandDefinitions.java`
- Modify: `src/main/java/org/example/bot/SlashCommandHandler.java`
- Modify: `src/test/java/org/example/bot/SlashCommandDefinitionsTest.java`

**Interfaces:**
- `SetupCommandHandler(GuildConfigRepository, Consumer<String> refreshDaily)` routes `setup` subcommands and `config`.
- `SetupValidation.parseZone(String)`, `parseDate(String)`, and patch-building methods reject invalid or empty updates before writes.
- Slash definitions include `setup` with subcommands `daily`, `call`, `archive`, default permission `MANAGE_SERVER`, and guild-only `config`.

- [ ] **Step 1: Write failing definition tests**

Assert exact subcommand names, option types, optional flags, integer range 1–10, role-action choices, required archive boolean, and setup default permission.

- [ ] **Step 2: Verify RED, implement definitions, verify GREEN**

Run: `mvn -Dtest=SlashCommandDefinitionsTest test`; expect missing definitions, implement them, rerun, and expect pass.

- [ ] **Step 3: Write failing validation and permission tests**

Cover valid/invalid IANA zones, ISO dates, blank prefix/message, no-op patch rejection, channel/role guild ownership, Manage Server success, Administrator success, ordinary-member denial, and DM denial.

- [ ] **Step 4: Verify RED, implement validation and permission gate, verify GREEN**

Run: `mvn -Dtest=SetupValidationTest,SetupCommandHandlerTest test`; expect missing behavior, implement minimal routing and validation, then rerun until green.

- [ ] **Step 5: Write failing partial-update and config-output tests**

Assert each optional field changes independently, role add/remove/clear semantics, enabling prerequisites, scheduler refresh only after daily changes, and ephemeral `/config` output containing current settings without mutation.

- [ ] **Step 6: Verify RED, implement persistence calls, verify GREEN**

Run focused setup tests before and after implementation; then run `mvn -Dtest=SlashCommandDefinitionsTest,SetupValidationTest,SetupCommandHandlerTest,SlashCommandHandlerTest test` and expect all pass.

- [ ] **Step 7: Commit**

Run: `git add src/main/java/org/example/bot src/test/java/org/example/bot && git commit -m "feat: add typed guild setup commands"`

### Task 7: One-Executor Multi-Guild Daily Scheduler

**Files:**
- Create: `src/main/java/org/example/bot/MultiGuildDailyMessageScheduler.java`
- Create: `src/test/java/org/example/bot/MultiGuildDailyMessageSchedulerTest.java`
- Modify: `src/main/java/org/example/bot/ScheduledMessageConfig.java`
- Remove: `src/main/java/org/example/bot/DailyMessageScheduler.java`
- Remove: `src/test/java/org/example/bot/DailyMessageSchedulerTest.java`

**Interfaces:**
- `MultiGuildDailyMessageScheduler(JDA, GuildConfigRepository)` for production.
- Test seam accepts `Clock`, one `TaskScheduler`, and a sender resolving guild and channel.
- Produces `start()`, `refreshGuild(String guildId)`, `removeGuild(String guildId)`, `shutdown()`, and static `nextRunAfter(ZonedDateTime now, ZoneId zone)`.

- [ ] **Step 1: Write failing timing/isolation tests**

Test distinct next midnights for two zones, startup scheduling for every active enabled guild, disabled/inactive exclusion, refresh replacing only one guild job, removal cancellation, and one shared task scheduler.

- [ ] **Step 2: Verify RED**

Run: `mvn -Dtest=MultiGuildDailyMessageSchedulerTest test`

Expected: compilation fails because the scheduler does not exist.

- [ ] **Step 3: Implement scheduling map and lifecycle**

Maintain `ConcurrentHashMap<String, ScheduledFuture<?>>`; schedule each guild on the shared executor; calculate local next midnight; cancel old futures on refresh; and do not create executors per guild.

- [ ] **Step 4: Write failing catch-up/retry/state tests**

Test per-guild last dates, bounded startup catch-up, no duplicate for today's state, state update only after success, retry delays 1/2/4 minutes, in-flight duplicate prevention, and rejection when resolved channel belongs to a different guild.

- [ ] **Step 5: Verify RED, implement delivery, verify GREEN**

Run focused scheduler tests before and after implementation. Build text from each row's prefix/base date/base number and local date. After green, run all bot tests to ensure removal of the old scheduler does not break unrelated behavior.

- [ ] **Step 6: Commit**

Run: `git add src/main/java/org/example/bot src/test/java/org/example/bot && git commit -m "feat: schedule daily messages per guild"`

### Task 8: Application Wiring and Startup Bootstrap

**Files:**
- Modify: `src/main/java/org/example/Main.java`
- Create: `src/main/java/org/example/bot/LegacyConfigLoader.java`
- Create: `src/test/java/org/example/bot/LegacyConfigLoaderTest.java`

**Interfaces:**
- `LegacyConfigLoader.fromEnvironment(Map<String,String>)` returns optional guild ID plus a `LegacyGuildConfig` based on existing `ScheduledMessageConfig` and `AdminCommandConfig` values.
- Main constructs one connection factory, migrator, configuration repository, archive repository, scheduler, listeners, and handlers.

- [ ] **Step 1: Write failing legacy environment tests**

Test missing/blank `LEGACY_GUILD_ID`, supplied ID, legacy daily channel override, and absence of hardcoded guild IDs.

- [ ] **Step 2: Verify RED, implement loader, verify GREEN**

Run: `mvn -Dtest=LegacyConfigLoaderTest test`; implement explicit environment parsing and rerun until pass.

- [ ] **Step 3: Wire startup**

Run migrations before JDA build. After `awaitReady`, bootstrap legacy configuration once, activate every `jda.getGuilds()` entry without overwriting settings, start the scheduler, register message/setup/lifecycle listeners, and install one shutdown hook. Remove production use of global daily and call configuration except through the legacy loader.

- [ ] **Step 4: Compile and run complete tests**

Run: `mvn clean test`

Expected: all tests pass with no compilation errors.

- [ ] **Step 5: Commit**

Run: `git add src/main/java/org/example src/test/java/org/example && git commit -m "feat: wire multi-guild bot startup"`

### Task 9: Documentation and Final Verification

**Files:**
- Modify: `README.md`

**Interfaces:**
- Documents `/setup`, `/config`, safe defaults, `LEGACY_GUILD_ID`, guild leave retention, permissions, SQLite migration, and per-guild behavior.

- [ ] **Step 1: Update operational documentation**

Remove instructions that treat `DAILY_CHANNEL_ID` and `CALL_ALLOWED_ROLE_IDS` as production configuration. Explain that they are legacy-bootstrap inputs, that archive collection is opt-in, DMs are not archived, and bot removal marks a guild inactive without deleting its data.

- [ ] **Step 2: Run static checks**

Run: `rg -n "countMessages\(\)|countMessagesByAuthor\([^,]+\)|findRecentMessages\([^,]+,[^,]+\)|getLastDailyMessageDate\(\)|setLastDailyMessageDate\([^,]+\)|AdminCommandConfig\.CALL|ScheduledMessageConfig\.DAILY_CHANNEL_ID" src/main/java`

Expected: no production command/scheduler use of global or unscoped APIs; legacy loader references are the only allowed configuration matches.

- [ ] **Step 3: Run full verification**

Run: `mvn clean test`

Expected: all tests pass.

Run: `mvn clean package`

Expected: `BUILD SUCCESS` and the executable jar is created.

Run: `git diff --check`

Expected: no whitespace errors.

- [ ] **Step 4: Commit documentation**

Run: `git add README.md && git commit -m "docs: explain multi-guild configuration"`

- [ ] **Step 5: Review final scope**

Run: `git status --short` and `git log --oneline --decorate -12`.

Expected: working tree clean and commits limited to the specification, plan, multi-guild implementation, tests, and documentation.
