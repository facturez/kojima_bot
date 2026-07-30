# Themed Slash Command Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the themed `/deport`, `/magadan`, and `/kpz` commands execute the existing ban, kick, and timeout behavior while preserving the new names and descriptions.

**Architecture:** Introduce one package-private command-contract class containing the public command and option names. Both JDA registration and event routing consume that contract, while tests hardcode the expected public strings so accidental renames remain detectable.

**Tech Stack:** Java 17, JDA 5.0.0-beta.24, JUnit Jupiter 5.10.2, Maven

## Global Constraints

- Preserve the exact command names `deport`, `magadan`, and `kpz`.
- Preserve the option names `чел` and `причина`; rename `duration` to `срок`.
- Preserve the current themed command descriptions and themed `/ping` response.
- Do not weaken Administrator, protected-target, role-hierarchy, or bot-permission checks.
- Existing `/help`, `/ping`, `/stats`, `/last`, `/зов`, `/clear`, and all `!` commands remain operational.
- Do not add runtime dependencies or unrelated refactors.

---

### Task 1: Align command registration, routing, options, and tests

**Files:**
- Create: `src/main/java/org/example/bot/SlashCommandContract.java`
- Modify: `src/main/java/org/example/bot/SlashCommandDefinitions.java`
- Modify: `src/main/java/org/example/bot/SlashCommandHandler.java`
- Modify: `src/test/java/org/example/bot/SlashCommandDefinitionsTest.java`
- Modify: `src/test/java/org/example/bot/SlashCommandHandlerTest.java`
- Modify: `src/test/java/org/example/bot/MessageListenerTest.java`

**Interfaces:**
- Produces package-private constants `DEPORT`, `MAGADAN`, `KPZ`, `TARGET_OPTION`, `REASON_OPTION`, and `DURATION_OPTION`.
- `SlashCommandDefinitions` and `SlashCommandHandler` consume those constants.
- Tests intentionally assert literal public values rather than importing the constants.

- [ ] **Step 1: Update tests to state the themed public contract**

Use exact command expectations:

```java
assertEquals(
        Set.of("help", "ping", "stats", "last", "зов", "clear", "deport", "magadan", "kpz"),
        commandNames
);
```

Assert options:

```java
assertOption(command("deport"), "чел", OptionType.USER, true);
assertOption(command("deport"), "причина", OptionType.STRING, false);
assertOption(command("magadan"), "чел", OptionType.USER, true);
assertOption(command("magadan"), "причина", OptionType.STRING, false);
assertOption(command("kpz"), "чел", OptionType.USER, true);
assertOption(command("kpz"), "срок", OptionType.STRING, true);
assertOption(command("kpz"), "причина", OptionType.STRING, false);
```

Rename moderation-handler test inputs from `ban`/`kick`/`timeout` and
`user`/`reason`/`duration` to
`deport`/`magadan`/`kpz` and `чел`/`причина`/`срок`. Keep assertions for
`BAN_MEMBERS`, `KICK_MEMBERS`, `MODERATE_MEMBERS`, action type, audit reason,
duration, denials, acknowledgement ordering, and sanitized failures.

Update ping expectations in both handler and listener tests to:

```text
Pong! Бот у апппарата. Слушает батву
```

Assert `/help` contains `/deport чел [причина]`, `/magadan чел [причина]`,
and `/kpz чел срок [причина]`.

- [ ] **Step 2: Run focused tests and verify they expose production drift**

Run:

```bash
mvn -Dtest=SlashCommandDefinitionsTest,SlashCommandHandlerTest,MessageListenerTest test
```

Expected: FAIL because production still registers `duration`, routes only
`ban`/`kick`/`timeout`, and reads `user`/`reason`.

- [ ] **Step 3: Add the shared command contract**

Create:

```java
package org.example.bot;

final class SlashCommandContract {
    static final String DEPORT = "deport";
    static final String MAGADAN = "magadan";
    static final String KPZ = "kpz";
    static final String TARGET_OPTION = "чел";
    static final String REASON_OPTION = "причина";
    static final String DURATION_OPTION = "срок";

    private SlashCommandContract() {
    }
}
```

- [ ] **Step 4: Use the contract in definitions and routing**

In `SlashCommandDefinitions`, retain the current themed descriptions and replace
literal command/option names with `SlashCommandContract` constants. In
`SlashCommandHandler`:

```java
case SlashCommandContract.DEPORT,
     SlashCommandContract.MAGADAN,
     SlashCommandContract.KPZ -> moderateMember(event);
```

Map permissions:

```java
Permission required = switch (event.getName()) {
    case SlashCommandContract.DEPORT -> Permission.BAN_MEMBERS;
    case SlashCommandContract.MAGADAN -> Permission.KICK_MEMBERS;
    case SlashCommandContract.KPZ -> Permission.MODERATE_MEMBERS;
    default -> throw new IllegalArgumentException("Unknown moderation command");
};
```

Read `TARGET_OPTION`, `REASON_OPTION`, and `DURATION_OPTION`. Select ban, kick,
and timeout actions using the same themed command constants. Update only the
slash help lines so their shown parameter names match the registered contract.

- [ ] **Step 5: Run focused and full tests**

Run:

```bash
mvn -Dtest=SlashCommandDefinitionsTest,SlashCommandHandlerTest,MessageListenerTest test
mvn test
```

Expected: focused tests and all repository tests PASS.

- [ ] **Step 6: Commit the aligned runtime and tests**

```bash
git add src/main/java/org/example/bot/SlashCommandContract.java \
  src/main/java/org/example/bot/SlashCommandDefinitions.java \
  src/main/java/org/example/bot/SlashCommandHandler.java \
  src/test/java/org/example/bot/SlashCommandDefinitionsTest.java \
  src/test/java/org/example/bot/SlashCommandHandlerTest.java \
  src/test/java/org/example/bot/MessageListenerTest.java
git commit -m "fix: align themed slash command routing"
```

### Task 2: Align documentation and verify the distributable

**Files:**
- Modify: `README.md`
- Modify: `src/main/java/org/example/bot/CommandHandler.java`

**Interfaces:**
- Documents the same themed public contract without changing runtime behavior.

- [ ] **Step 1: Update user-facing command references**

Replace only obsolete slash examples:

```text
/deport чел [причина]
/magadan чел [причина]
/kpz чел срок [причина]
```

Retain the current thematic descriptions. Do not alter the personal legacy
`!last` and `!зов` lines or any `!` command routing.

- [ ] **Step 2: Verify no obsolete moderation slash contract remains**

Run:

```bash
rg -n '/(ban|kick|timeout)\b|OptionMapping.*"(user|reason|duration)"' \
  README.md src/main/java src/test/java
```

Expected: no obsolete public command or option lookup remains; references that
explicitly test unknown-command behavior are allowed only when labeled as such.

- [ ] **Step 3: Run clean verification**

Run:

```bash
git diff --check
mvn clean test
mvn clean package
```

Expected: 0 test failures and
`target/kojima_bot-1.0-SNAPSHOT-jar-with-dependencies.jar` exists.

- [ ] **Step 4: Commit documentation**

```bash
git add README.md src/main/java/org/example/bot/CommandHandler.java
git commit -m "docs: describe themed slash commands"
```
