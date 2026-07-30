# Slash and Moderation Commands Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register slash equivalents of the existing bot commands and add safe administrator-only ban, kick, and timeout commands.

**Architecture:** Keep `CommandHandler` as the compatibility adapter for `!` commands and add a focused slash adapter. Put command definitions, timeout parsing, and moderation authorization in small testable classes so both adapters rely on the same validation rules instead of embedding policy in Discord callbacks.

**Tech Stack:** Java 17, JDA 5.0.0-beta.24, JUnit Jupiter 5.10.2, Maven

## Global Constraints

- Existing `!help`, `!ping`, `!stats`, `!last`, `!зов`, `!clear`, and `!очистить` commands remain enabled.
- Slash commands are global and may take time to appear in Discord.
- Ban, kick, and timeout require the caller's Discord `Administrator` permission.
- Timeout duration accepts positive integer values suffixed by `m`, `h`, or `d`, from one minute through 28 days inclusive.
- Never overwrite the user's existing uncommitted help-text edits in `src/main/java/org/example/bot/CommandHandler.java`.
- No new runtime dependencies are required.

---

### Task 1: Timeout duration parser

**Files:**
- Create: `src/main/java/org/example/bot/TimeoutDurationParser.java`
- Create: `src/test/java/org/example/bot/TimeoutDurationParserTest.java`

**Interfaces:**
- Produces: `static Duration parse(String raw)`; throws `IllegalArgumentException` with a user-safe Russian message for invalid input.

- [ ] **Step 1: Write failing parser tests**

```java
@ParameterizedTest
@CsvSource({"1m,PT1M", "30m,PT30M", "2h,PT2H", "28d,PT672H"})
void parsesSupportedDurations(String raw, String expected) {
    assertEquals(Duration.parse(expected), TimeoutDurationParser.parse(raw));
}

@ParameterizedTest
@ValueSource(strings = {"", "0m", "-1m", "1s", "29d", "abc", "999999999999999999999d"})
void rejectsUnsupportedDurations(String raw) {
    assertThrows(IllegalArgumentException.class, () -> TimeoutDurationParser.parse(raw));
}
```

- [ ] **Step 2: Run the focused test and confirm the class is missing**

Run: `mvn -Dtest=TimeoutDurationParserTest test`

Expected: compilation failure because `TimeoutDurationParser` does not exist.

- [ ] **Step 3: Implement strict parsing and bounds**

```java
public final class TimeoutDurationParser {
    private static final Pattern FORMAT = Pattern.compile("^(\\d+)([mhd])$");
    private static final Duration MINIMUM = Duration.ofMinutes(1);
    private static final Duration MAXIMUM = Duration.ofDays(28);

    public static Duration parse(String raw) {
        Matcher matcher = FORMAT.matcher(raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw invalid();
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            Duration duration = switch (matcher.group(2)) {
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw invalid();
            };
            if (duration.compareTo(MINIMUM) < 0 || duration.compareTo(MAXIMUM) > 0) {
                throw invalid();
            }
            return duration;
        } catch (ArithmeticException | NumberFormatException failure) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Длительность должна быть от 1m до 28d. Доступные единицы: m, h, d.");
    }
}
```

- [ ] **Step 4: Run the parser tests**

Run: `mvn -Dtest=TimeoutDurationParserTest test`

Expected: PASS.

- [ ] **Step 5: Commit the parser**

```bash
git add src/main/java/org/example/bot/TimeoutDurationParser.java src/test/java/org/example/bot/TimeoutDurationParserTest.java
git commit -m "feat: parse moderation timeout durations"
```

### Task 2: Moderation authorization policy

**Files:**
- Modify: `src/main/java/org/example/bot/CommandAuthorization.java`
- Modify: `src/test/java/org/example/bot/CommandAuthorizationTest.java`

**Interfaces:**
- Produces: `static ModerationDenial checkModeration(boolean fromGuild, boolean administrator, boolean selfTarget, boolean ownerTarget, boolean botSelfTarget, boolean callerCanInteract, boolean botCanInteract, boolean botHasPermission)`.
- Produces nested enum: `ModerationDenial { NONE, GUILD_ONLY, ADMINISTRATOR_REQUIRED, SELF_TARGET, OWNER_TARGET, BOT_SELF_TARGET, CALLER_HIERARCHY, BOT_HIERARCHY, BOT_PERMISSION }`.

- [ ] **Step 1: Add one test per denial and an allowed case**

```java
@Test
void moderationRequiresEverySafetyCondition() {
    assertEquals(NONE, checkModeration(true, true, false, false, false, true, true, true));
    assertEquals(GUILD_ONLY, checkModeration(false, true, false, false, false, true, true, true));
    assertEquals(ADMINISTRATOR_REQUIRED, checkModeration(true, false, false, false, false, true, true, true));
    assertEquals(SELF_TARGET, checkModeration(true, true, true, false, false, true, true, true));
    assertEquals(OWNER_TARGET, checkModeration(true, true, false, true, false, true, true, true));
    assertEquals(BOT_SELF_TARGET, checkModeration(true, true, false, false, true, true, true, true));
    assertEquals(CALLER_HIERARCHY, checkModeration(true, true, false, false, false, false, true, true));
    assertEquals(BOT_HIERARCHY, checkModeration(true, true, false, false, false, true, false, true));
    assertEquals(BOT_PERMISSION, checkModeration(true, true, false, false, false, true, true, false));
}
```

- [ ] **Step 2: Run the authorization test and confirm it fails**

Run: `mvn -Dtest=CommandAuthorizationTest test`

Expected: compilation failure for the missing enum and method.

- [ ] **Step 3: Implement the ordered policy**

Implement `checkModeration` with the exact enum above. Return the first applicable denial in this order: guild, administrator, self, owner, bot self, caller hierarchy, bot hierarchy, bot permission; otherwise return `NONE`.

- [ ] **Step 4: Run authorization tests**

Run: `mvn -Dtest=CommandAuthorizationTest test`

Expected: PASS, including all existing authorization cases.

- [ ] **Step 5: Commit the policy**

```bash
git add src/main/java/org/example/bot/CommandAuthorization.java src/test/java/org/example/bot/CommandAuthorizationTest.java
git commit -m "feat: define moderation authorization policy"
```

### Task 3: Slash command definitions and registration

**Files:**
- Create: `src/main/java/org/example/bot/SlashCommandDefinitions.java`
- Create: `src/test/java/org/example/bot/SlashCommandDefinitionsTest.java`
- Modify: `src/main/java/org/example/Main.java`

**Interfaces:**
- Produces: `static List<CommandData> all()`.
- `Main` consumes this list through `jda.updateCommands().addCommands(SlashCommandDefinitions.all()).queue(...)`.

- [ ] **Step 1: Write failing definition tests**

Assert that `all()` defines exactly `help`, `ping`, `stats`, `last`, `зов`, `clear`, `ban`, `kick`, and `timeout`. Assert:

```java
assertOption(command("last"), "count", OptionType.INTEGER, false);
assertOption(command("clear"), "count", OptionType.INTEGER, false);
assertOption(command("ban"), "user", OptionType.USER, true);
assertOption(command("ban"), "reason", OptionType.STRING, false);
assertOption(command("kick"), "user", OptionType.USER, true);
assertOption(command("kick"), "reason", OptionType.STRING, false);
assertOption(command("timeout"), "user", OptionType.USER, true);
assertOption(command("timeout"), "duration", OptionType.STRING, true);
assertOption(command("timeout"), "reason", OptionType.STRING, false);
```

Also assert integer bounds `1..20` for `/last count` and `1..100` for `/clear count`.

- [ ] **Step 2: Run the definition tests and confirm failure**

Run: `mvn -Dtest=SlashCommandDefinitionsTest test`

Expected: compilation failure because the definitions class does not exist.

- [ ] **Step 3: Build command data with JDA's `Commands.slash`**

Use `OptionData` for the options above, Russian descriptions, and the exact numeric bounds. Keep the list immutable.

- [ ] **Step 4: Register definitions during startup**

After `awaitReady()`, call:

```java
jda.updateCommands()
        .addCommands(SlashCommandDefinitions.all())
        .queue(
                commands -> System.out.println("Registered " + commands.size() + " global slash commands."),
                failure -> System.err.println("Failed to register slash commands: " + failure.getMessage())
        );
```

- [ ] **Step 5: Run definition tests and the full existing suite**

Run: `mvn -Dtest=SlashCommandDefinitionsTest test && mvn test`

Expected: PASS.

- [ ] **Step 6: Commit definitions and startup registration**

```bash
git add src/main/java/org/example/Main.java src/main/java/org/example/bot/SlashCommandDefinitions.java src/test/java/org/example/bot/SlashCommandDefinitionsTest.java
git commit -m "feat: register global slash commands"
```

### Task 4: Slash handler for existing commands

**Files:**
- Create: `src/main/java/org/example/bot/SlashCommandHandler.java`
- Create: `src/test/java/org/example/bot/SlashCommandHandlerTest.java`
- Modify: `src/main/java/org/example/bot/MessageListener.java`

**Interfaces:**
- Produces: `void handle(SlashCommandInteractionEvent event)`.
- `MessageListener` owns one `SlashCommandHandler` and forwards `onSlashCommandInteraction`.

- [ ] **Step 1: Write routing and permission tests**

Use dynamic JDA proxies, following `CommandHandlerTest`, to verify:

```java
handler.handle(eventNamed("ping"));
assertEquals("Pong! Бот на связи.", reply.get());

handler.handle(eventNamed("stats"));
assertTrue(reply.get().contains("Статистика базы:"));

handler.handle(directMessageEventNamed("last"));
assertTrue(reply.get().contains("только на сервере"));
```

Add cases for `/help`, default and explicit `/last count`, `/зов`, and default and explicit `/clear count`. Verify `/last` uses channel-scoped `MESSAGE_MANAGE` and `/clear` requires `Administrator`.

- [ ] **Step 2: Run the slash handler tests and confirm failure**

Run: `mvn -Dtest=SlashCommandHandlerTest test`

Expected: compilation failure because `SlashCommandHandler` does not exist.

- [ ] **Step 3: Implement routing and acknowledged replies**

Route on `event.getName()`. For fast local responses use:

```java
event.reply(content).queue(
        null,
        failure -> System.err.println("Failed to reply to slash command: " + failure.getMessage())
);
```

For archive reads and Discord deletion operations, call `event.deferReply()` first and finish through the returned hook. Reuse `CommandAuthorization.canReadArchive`, `AdminCommandConfig`, repository queries, the same limits and the same 14-day deletion rule as `CommandHandler`.

- [ ] **Step 4: Forward slash events from the listener**

```java
@Override
public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    slashCommandHandler.handle(event);
}
```

Initialize the handler from the same repository in `MessageListener`'s constructor.

- [ ] **Step 5: Run focused and full tests**

Run: `mvn -Dtest=SlashCommandHandlerTest,MessageListenerTest test && mvn test`

Expected: PASS.

- [ ] **Step 6: Commit existing-command slash support**

```bash
git add src/main/java/org/example/bot/SlashCommandHandler.java src/main/java/org/example/bot/MessageListener.java src/test/java/org/example/bot/SlashCommandHandlerTest.java src/test/java/org/example/bot/MessageListenerTest.java
git commit -m "feat: handle existing commands through slash interactions"
```

### Task 5: Ban, kick, and timeout execution

**Files:**
- Modify: `src/main/java/org/example/bot/SlashCommandHandler.java`
- Modify: `src/test/java/org/example/bot/SlashCommandHandlerTest.java`

**Interfaces:**
- Consumes: `TimeoutDurationParser.parse(String)`.
- Consumes: `CommandAuthorization.checkModeration(...)`.
- Executes JDA `Guild.ban(UserSnowflake, int, TimeUnit)`, `Member.kick()`, and `Member.timeoutFor(Duration)`, applying `.reason(auditReason)` before `.queue(...)`.

- [ ] **Step 1: Add failing tests for authorization and target extraction**

Cover all `ModerationDenial` values through handler-level cases and verify no moderation action is queued on denial. Verify a missing resolved member gives a user-safe response. Verify `/ban`, `/kick`, and `/timeout` select the mentioned `user`, and `/timeout` parses the `duration`.

- [ ] **Step 2: Add failing success and Discord-failure tests**

Capture the JDA rest action and assert:

```java
assertEquals("спам", recordedAuditReason.get());
assertTrue(recordedConfirmation.get().contains(target.getAsMention()));
```

Invoke the captured failure consumer with `new IllegalStateException("Discord unavailable")` and assert the reply is a generic Russian error that does not include the exception text.

- [ ] **Step 3: Run focused tests and confirm failure**

Run: `mvn -Dtest=SlashCommandHandlerTest test`

Expected: FAIL because moderation routes are not implemented.

- [ ] **Step 4: Implement shared moderation preflight**

Map command to required bot permission:

```java
Permission required = switch (event.getName()) {
    case "ban" -> Permission.BAN_MEMBERS;
    case "kick" -> Permission.KICK_MEMBERS;
    case "timeout" -> Permission.MODERATE_MEMBERS;
    default -> throw new IllegalArgumentException("Unknown moderation command");
};
```

Build `checkModeration` inputs from `event.isFromGuild()`, caller permission,
target IDs, `guild.getOwnerId()`, `caller.canInteract(target)`,
`guild.getSelfMember().canInteract(target)`, and
`guild.getSelfMember().hasPermission(required)`. Map every denial enum to a
fixed Russian response.

- [ ] **Step 5: Implement the three asynchronous actions**

Use the supplied reason after trimming; when blank, use
`"Действие выполнено администратором через slash-команду"`. Send one success
confirmation only from the REST action success callback and one generic failure
response from its failure callback.

- [ ] **Step 6: Run focused and full tests**

Run: `mvn -Dtest=SlashCommandHandlerTest,CommandAuthorizationTest,TimeoutDurationParserTest test && mvn test`

Expected: PASS.

- [ ] **Step 7: Commit moderation execution**

```bash
git add src/main/java/org/example/bot/SlashCommandHandler.java src/test/java/org/example/bot/SlashCommandHandlerTest.java
git commit -m "feat: add safe slash moderation actions"
```

### Task 6: Help, deployment documentation, and final verification

**Files:**
- Modify: `src/main/java/org/example/bot/CommandHandler.java`
- Modify: `src/main/java/org/example/bot/SlashCommandHandler.java`
- Modify: `README.md`

**Interfaces:**
- Documents slash command names, parameters, administrator requirement, bot permissions, hierarchy behavior, and retained `!` compatibility.

- [ ] **Step 1: Update both help responses**

List all slash commands, show `duration` examples `30m`, `2h`, and `7d`, and state that ban/kick/timeout require `Administrator`. Preserve the user's current wording changes where they do not conflict with the new command list.

- [ ] **Step 2: Update README**

Document installation permissions `Ban Members`, `Kick Members`, and `Moderate Members`; global registration delay; command syntax; 28-day timeout maximum; caller and bot role hierarchy; and retained `!` commands.

- [ ] **Step 3: Run formatting and repository checks**

Run: `git diff --check`

Expected: no output.

- [ ] **Step 4: Run all tests and package**

Run: `mvn clean test && mvn package`

Expected: both commands exit 0 and create `target/kojima_bot-1.0-SNAPSHOT-jar-with-dependencies.jar`.

- [ ] **Step 5: Review the final diff**

Run: `git status --short && git diff --stat && git diff`

Expected: only feature-related source, tests, README, and the user's pre-existing help-text lines are present.

- [ ] **Step 6: Commit documentation and integration**

```bash
git add README.md src/main/java/org/example/bot/CommandHandler.java src/main/java/org/example/bot/SlashCommandHandler.java
git commit -m "docs: explain slash moderation commands"
```
