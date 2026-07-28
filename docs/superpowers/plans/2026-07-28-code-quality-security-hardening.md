# Kojima Bot Code Quality and Security Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Исправить подтверждённые ошибки авторизации и хранения сообщений, вернуть московское расписание, улучшить обработку ошибок и документировать минимальные права без крупной перестройки приложения.

**Architecture:** Чистые решения об авторизации и конфигурации отделяются от JDA-вызовов, чтобы их можно было тестировать без Discord. `MessageRepository` остаётся SQLite-адаптером, но получает явную retention-политику и операции удаления; `MessageListener` зеркалирует события удаления Discord. Планировщик сохраняет текущую модель повторного планирования ближайшей полуночи.

**Tech Stack:** Java 17, Maven, JDA 5, SQLite JDBC, JUnit Jupiter 5.

## Global Constraints

- Использовать часовой пояс `Europe/Moscow`; часовой пояс хоста не должен влиять на ежедневное сообщение.
- Сохранить текст `!зов`, базовую дату `2026-04-23`, базовый номер дня `365` и существующие имена команд.
- Авторизовывать `!зов` только через Discord role ID или право `Administrator`; имя роли не является идентификатором.
- Требовать у пользователя `Manage Messages` для `!last`; права пользователя и права бота документировать отдельно.
- Значение `MESSAGE_RETENTION_DAYS` по умолчанию равно `30` и должно быть положительным целым числом.
- Не добавлять внешнюю БД, slash-команды или платформенно-зависимую Java-логику Unix file mode.
- Не перезаписывать пользовательский текст в `AdminCommandConfig.java`; удалить только небезопасную настройку по имени роли.

---

## File Structure

- Create `src/main/java/org/example/bot/CommandAuthorization.java`: чистые решения об авторизации команд.
- Create `src/test/java/org/example/bot/CommandAuthorizationTest.java`: тесты role ID и `Manage Messages`.
- Create `src/main/java/org/example/db/MessageRetentionConfig.java`: разбор `MESSAGE_RETENTION_DAYS`.
- Create `src/test/java/org/example/db/MessageRetentionConfigTest.java`: тесты конфигурации retention.
- Create `src/test/java/org/example/db/MessageRepositoryTest.java`: интеграционные тесты временной SQLite-базы.
- Create `src/test/java/org/example/bot/DailyMessageSchedulerTest.java`: тест расчёта ближайшей московской полуночи.
- Modify `src/main/java/org/example/bot/AdminCommandConfig.java`: оставить только allowlist role ID.
- Modify `src/main/java/org/example/bot/CommandHandler.java`: применять новые политики и безопасно сообщать об ошибках репозитория.
- Modify `src/main/java/org/example/bot/MessageListener.java`: удалять архивные записи по Discord delete events.
- Modify `src/main/java/org/example/bot/ScheduledMessageConfig.java`: вернуть `Europe/Moscow`, удалить незавершённый метод.
- Modify `src/main/java/org/example/bot/DailyMessageScheduler.java`: выделить чистый расчёт следующего запуска.
- Modify `src/main/java/org/example/db/MessageRepository.java`: retention, удаление и тестовая вставка данных.
- Modify `src/main/java/org/example/Main.java`: валидировать retention до подключения к Discord.
- Modify `Dockerfile`: запускать процесс под непривилегированным пользователем и хранить SQLite в отдельном каталоге.
- Modify `README.md` and `kojima_bot/README.md`: синхронизировать конфигурацию, retention и права.

### Task 1: Московское расписание и чистый расчёт следующего запуска

**Files:**
- Modify: `src/main/java/org/example/bot/ScheduledMessageConfig.java`
- Modify: `src/main/java/org/example/bot/DailyMessageScheduler.java`
- Modify: `src/test/java/org/example/bot/ScheduledMessageConfigTest.java`
- Create: `src/test/java/org/example/bot/DailyMessageSchedulerTest.java`

**Interfaces:**
- Produces: `static ZonedDateTime nextRunAfter(ZonedDateTime now, ZoneId zoneId)`.
- Preserves: `ScheduledMessageConfig.buildDailyMessageText(LocalDate)`.

- [ ] **Step 1: Write failing Moscow-zone and next-run tests**

```java
@Test
void usesMoscowTimeZone() {
    assertEquals(ZoneId.of("Europe/Moscow"), ScheduledMessageConfig.TIME_ZONE);
}

@Test
void schedulesNextMoscowMidnightIndependentlyOfInputOffset() {
    ZonedDateTime now = ZonedDateTime.parse("2026-07-28T23:30:00+02:00[Europe/Berlin]");
    ZonedDateTime next = DailyMessageScheduler.nextRunAfter(now, ZoneId.of("Europe/Moscow"));
    assertEquals(ZonedDateTime.parse("2026-07-29T00:00:00+03:00[Europe/Moscow]"), next);
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `mvn -Dtest=ScheduledMessageConfigTest,DailyMessageSchedulerTest test`

Expected: failure because the current zone is `Europe/Berlin` and `nextRunAfter` does not exist.

- [ ] **Step 3: Implement the minimal scheduling change**

Set `TIME_ZONE` to `ZoneId.of("Europe/Moscow")`, delete `getFarAvay`, and add:

```java
static ZonedDateTime nextRunAfter(ZonedDateTime now, ZoneId zoneId) {
    ZonedDateTime zonedNow = now.withZoneSameInstant(zoneId);
    return zonedNow.toLocalDate().plusDays(1).atStartOfDay(zoneId);
}
```

Use this method from `scheduleNextRun()`.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run: `mvn -Dtest=ScheduledMessageConfigTest,DailyMessageSchedulerTest test`

Expected: all focused tests pass.

- [ ] **Step 5: Commit the scheduling unit**

```bash
git add src/main/java/org/example/bot/ScheduledMessageConfig.java src/main/java/org/example/bot/DailyMessageScheduler.java src/test/java/org/example/bot/ScheduledMessageConfigTest.java src/test/java/org/example/bot/DailyMessageSchedulerTest.java
git commit -m "fix: schedule daily message in Moscow time"
```

### Task 2: Стабильная авторизация `!зов` и модераторский доступ к `!last`

**Files:**
- Create: `src/main/java/org/example/bot/CommandAuthorization.java`
- Create: `src/test/java/org/example/bot/CommandAuthorizationTest.java`
- Modify: `src/main/java/org/example/bot/AdminCommandConfig.java`
- Modify: `src/main/java/org/example/bot/CommandHandler.java`

**Interfaces:**
- Produces: `static boolean canCallEveryone(boolean administrator, Collection<String> memberRoleIds, Collection<String> allowedRoleIds)`.
- Produces: `static boolean canReadArchive(boolean fromGuild, boolean manageMessages)`.

- [ ] **Step 1: Write failing policy tests**

```java
@Test
void sameRoleNameCannotGrantCallAccess() {
    assertFalse(CommandAuthorization.canCallEveryone(false, List.of("222"), List.of("111")));
}

@Test
void configuredRoleIdGrantsCallAccess() {
    assertTrue(CommandAuthorization.canCallEveryone(false, List.of("111"), List.of("111")));
}

@Test
void emptyRoleAllowlistDeniesNonAdministrator() {
    assertFalse(CommandAuthorization.canCallEveryone(false, List.of("111"), List.of()));
}

@Test
void administratorCanCallWithEmptyAllowlist() {
    assertTrue(CommandAuthorization.canCallEveryone(true, List.of(), List.of()));
}

@Test
void archiveRequiresGuildManageMessagesPermission() {
    assertTrue(CommandAuthorization.canReadArchive(true, true));
    assertFalse(CommandAuthorization.canReadArchive(true, false));
    assertFalse(CommandAuthorization.canReadArchive(false, true));
}
```

- [ ] **Step 2: Run policy tests and verify RED**

Run: `mvn -Dtest=CommandAuthorizationTest test`

Expected: compilation failure because `CommandAuthorization` does not exist.

- [ ] **Step 3: Implement the pure policy**

```java
public final class CommandAuthorization {
    private CommandAuthorization() {}

    public static boolean canCallEveryone(
            boolean administrator,
            Collection<String> memberRoleIds,
            Collection<String> allowedRoleIds
    ) {
        return administrator || memberRoleIds.stream().anyMatch(allowedRoleIds::contains);
    }

    public static boolean canReadArchive(boolean fromGuild, boolean manageMessages) {
        return fromGuild && manageMessages;
    }
}
```

Remove `CALL_ALLOWED_ROLE_NAMES`. In `CommandHandler`, map `member.getRoles()` to IDs and use the policy. Before `sendRecentMessages`, require a guild member with `Permission.MESSAGE_MANAGE`; return a clear denial message otherwise.

- [ ] **Step 4: Run policy and existing tests**

Run: `mvn -Dtest=CommandAuthorizationTest,ScheduledMessageConfigTest test`

Expected: all selected tests pass.

- [ ] **Step 5: Commit the authorization unit**

```bash
git add src/main/java/org/example/bot/CommandAuthorization.java src/main/java/org/example/bot/AdminCommandConfig.java src/main/java/org/example/bot/CommandHandler.java src/test/java/org/example/bot/CommandAuthorizationTest.java
git commit -m "fix: use stable Discord authorization"
```

### Task 3: Retention и SQLite-операции удаления

**Files:**
- Create: `src/main/java/org/example/db/MessageRetentionConfig.java`
- Create: `src/test/java/org/example/db/MessageRetentionConfigTest.java`
- Create: `src/test/java/org/example/db/MessageRepositoryTest.java`
- Modify: `src/main/java/org/example/db/MessageRepository.java`
- Modify: `src/main/java/org/example/Main.java`

**Interfaces:**
- Produces: `static int parseDays(String rawValue)` with default `30` for null/blank.
- Produces: `void deleteMessage(String messageId)`.
- Produces: `void deleteMessages(Collection<String> messageIds)`.
- Produces: `int deleteExpiredMessages()`.
- Produces package-private test seam: `void saveStoredMessage(String messageId, String channelId, String guildId, String authorId, String authorTag, String content, Instant createdAt)`.
- Changes constructor to `MessageRepository(String databasePath, int retentionDays, Clock clock)`, while retaining `MessageRepository(String databasePath)` for production compatibility.

- [ ] **Step 1: Write failing retention-config tests**

```java
@Test
void defaultsToThirtyDays() {
    assertEquals(30, MessageRetentionConfig.parseDays(null));
    assertEquals(30, MessageRetentionConfig.parseDays(" "));
}

@Test
void acceptsPositiveDays() {
    assertEquals(7, MessageRetentionConfig.parseDays("7"));
}

@Test
void rejectsInvalidDays() {
    assertThrows(IllegalArgumentException.class, () -> MessageRetentionConfig.parseDays("0"));
    assertThrows(IllegalArgumentException.class, () -> MessageRetentionConfig.parseDays("-1"));
    assertThrows(IllegalArgumentException.class, () -> MessageRetentionConfig.parseDays("abc"));
}
```

- [ ] **Step 2: Write failing temporary-SQLite tests**

Use `@TempDir Path tempDir`, a fixed clock at `2026-07-28T12:00:00Z`, and a repository constructed with `retentionDays = 30`.

```java
@Test
void deletionRemovesOnlyMatchingMessage() {
    repository.saveStoredMessage("m1", "c1", "g1", "a1", "user", "one", NOW.minusSeconds(60));
    repository.saveStoredMessage("m2", "c1", "g1", "a1", "user", "two", NOW.minusSeconds(30));
    repository.deleteMessage("m1");
    assertEquals(List.of("two"), repository.findRecentMessages("c1", 20)
            .stream().map(StoredMessage::content).toList());
}

@Test
void expiredRowsAreNotReturned() {
    repository.saveStoredMessage("old", "c1", "g1", "a1", "user", "old", NOW.minus(31, ChronoUnit.DAYS));
    repository.saveStoredMessage("new", "c1", "g1", "a1", "user", "new", NOW.minus(1, ChronoUnit.DAYS));
    assertEquals(List.of("new"), repository.findRecentMessages("c1", 20)
            .stream().map(StoredMessage::content).toList());
}
```

- [ ] **Step 3: Run repository/config tests and verify RED**

Run: `mvn -Dtest=MessageRetentionConfigTest,MessageRepositoryTest test`

Expected: compilation failure because the new config and repository methods do not exist.

- [ ] **Step 4: Implement configuration and repository behavior**

`MessageRetentionConfig.parseDays` trims and parses the value, throws `IllegalArgumentException("MESSAGE_RETENTION_DAYS must be a positive integer")` for invalid input, and returns 30 when absent.

Refactor `saveMessage(Message)` to call `saveStoredMessage(...)`. Store `retentionDays` and `Clock`; run `deleteExpiredMessages()` after schema initialization and before `findRecentMessages`. Delete expired rows with:

```sql
DELETE FROM messages WHERE created_at < ?
```

Use `clock.instant().minus(retentionDays, ChronoUnit.DAYS)` as cutoff. Implement single and bulk deletion with prepared statements and a transaction. In `Main`, parse the environment value before JDA initialization and construct the repository with the validated days.

- [ ] **Step 5: Run repository/config tests and verify GREEN**

Run: `mvn -Dtest=MessageRetentionConfigTest,MessageRepositoryTest test`

Expected: all selected tests pass.

- [ ] **Step 6: Commit the storage lifecycle unit**

```bash
git add src/main/java/org/example/db/MessageRetentionConfig.java src/main/java/org/example/db/MessageRepository.java src/main/java/org/example/Main.java src/test/java/org/example/db/MessageRetentionConfigTest.java src/test/java/org/example/db/MessageRepositoryTest.java
git commit -m "feat: enforce message archive retention"
```

### Task 4: Синхронизация Discord deletion events и безопасные ошибки

**Files:**
- Modify: `src/main/java/org/example/bot/MessageListener.java`
- Modify: `src/main/java/org/example/bot/CommandHandler.java`
- Modify: `src/main/java/org/example/bot/DailyMessageScheduler.java`

**Interfaces:**
- Consumes: `MessageRepository.deleteMessage(String)` and `deleteMessages(Collection<String>)`.
- Preserves all existing command names and responses except explicit authorization/storage error messages.

- [ ] **Step 1: Add JDA delete-event handlers**

Implement:

```java
@Override
public void onMessageDelete(MessageDeleteEvent event) {
    deleteArchivedMessage(event.getMessageId());
}

@Override
public void onMessageBulkDelete(MessageBulkDeleteEvent event) {
    try {
        repository.deleteMessages(event.getMessageIds());
    } catch (RuntimeException failure) {
        System.err.println("Failed to delete archived messages: " + failure.getMessage());
    }
}
```

The single-delete helper catches `RuntimeException` and logs the message without rethrowing into JDA.

- [ ] **Step 2: Add explicit repository failure handling to commands**

Wrap repository operations in `sendStats` and `sendRecentMessages` with one `try/catch (RuntimeException failure)`. Log the detailed exception to stderr and send `Не получилось прочитать архив сообщений.` to Discord.

Replace the empty failure callback for deleting the invoking `!clear` message with:

```java
failure -> System.err.println("Failed to delete clear command message: " + failure.getMessage())
```

- [ ] **Step 3: Verify compilation and all tests**

Run: `mvn test`

Expected: all tests pass and JDA event method signatures compile.

- [ ] **Step 4: Commit event synchronization and error handling**

```bash
git add src/main/java/org/example/bot/MessageListener.java src/main/java/org/example/bot/CommandHandler.java src/main/java/org/example/bot/DailyMessageScheduler.java
git commit -m "fix: synchronize Discord message deletions"
```

### Task 5: Least-privilege deployment and documentation

**Files:**
- Modify: `Dockerfile`
- Modify: `README.md`
- Modify: `kojima_bot/README.md`

**Interfaces:**
- Documents: `DISCORD_TOKEN`, `BOT_DB_PATH`, `DAILY_CHANNEL_ID`, `MESSAGE_RETENTION_DAYS`.
- Documents separate user and bot permission sets.

- [ ] **Step 1: Inspect the current Docker stages and preserve the existing jar path**

Run: `sed -n '1,240p' Dockerfile`

Expected: identify the runtime base image, copied jar name, work directory, and entrypoint before editing.

- [ ] **Step 2: Run the runtime image as a dedicated non-root user**

Create `/app/data`, assign it to a dedicated UID/GID, set `BOT_DB_PATH=/app/data/bot-data.db`, switch to that user, and preserve the existing Java entrypoint. Do not grant broad group/world write permissions.

- [ ] **Step 3: Rewrite the permissions sections in both README files**

Document:

```text
Права пользователя:
- Manage Messages — для вызова !last.

Права бота:
- View Channel и Send Messages — для команд и ежедневного сообщения.
- Read Message History и Manage Messages — для !clear.
- Mention Everyone — только в каналах, где используется !зов.
```

State explicitly that `!last` reads SQLite and does not itself require the bot's `Read Message History`. Remove the Administrator shortcut. Document `Europe/Moscow`, role IDs, `MESSAGE_RETENTION_DAYS=30`, archive deletion behavior, and private volume/UID requirements.

- [ ] **Step 4: Verify README copies stay identical**

Run: `cmp README.md kojima_bot/README.md`

Expected: exit code 0.

- [ ] **Step 5: Build and inspect the image when Docker is available**

Run: `docker build -t kojima-bot:test .`

Expected: image builds successfully. If Docker is unavailable, record that exact limitation and continue with Maven verification.

- [ ] **Step 6: Commit deployment and documentation**

```bash
git add Dockerfile README.md kojima_bot/README.md
git commit -m "docs: document least-privilege bot deployment"
```

### Task 6: Final regression and scope verification

**Files:**
- Verify all modified files.

**Interfaces:**
- Consumes all prior task outputs.
- Produces a verified build artifact and clean scope report.

- [ ] **Step 1: Run the complete test suite**

Run: `mvn test`

Expected: zero failures and zero errors.

- [ ] **Step 2: Run the complete package build**

Run: `mvn clean package`

Expected: exit code 0 and `target/kojima_bot-1.0-SNAPSHOT-jar-with-dependencies.jar`.

- [ ] **Step 3: Inspect the final diff and whitespace**

Run: `git diff --check HEAD~5..HEAD`

Expected: no whitespace errors.

Run: `git status --short`

Expected: only any explicitly preserved user-owned changes remain; no generated database or build artifact is staged.

- [ ] **Step 4: Recheck security requirements**

Run:

```bash
rg -n 'CALL_ALLOWED_ROLE_NAMES|equalsIgnoreCase\\(allowedRoleName\\)|Для упрощения можно выдать.*Administrator|Europe/Berlin|getFarAvay' src README.md kojima_bot/README.md
```

Expected: no matches.

Run:

```bash
rg -n 'MESSAGE_RETENTION_DAYS|Europe/Moscow|CALL_ALLOWED_ROLE_IDS|MESSAGE_MANAGE|onMessageDelete|onMessageBulkDelete' src README.md kojima_bot/README.md
```

Expected: every required control is represented in code, tests, or documentation.

- [ ] **Step 5: Review every requirement in the approved design**

Check the implementation against `docs/superpowers/specs/2026-07-28-code-quality-security-hardening-design.md` and report any unmet requirement rather than claiming completion.
