# Kojima Bot

Простой Discord-бот на Java 17 и JDA с локальной SQLite-базой.

Проект уже умеет:
- читать и сохранять сообщения пользователей
- отправлять ежедневное сообщение в `00:00` по Москве
- автоматически считать дни в формате `день без сереги шиянова N`
- выполнять админскую команду `!зов`
- очищать чат через админскую команду `!clear`

## Стек

- Java 17+
- Maven
- JDA 5
- SQLite

## Команды

- `!help` - показать список команд
- `!ping` - проверить, что бот онлайн
- `!stats` - показать количество сообщений в базе
- `!last [n]` - показать последние `n` сообщений из текущего канала
- `!зов` - админская команда, тегает `@everyone` и отправляет заданный текст 3 раза
- `!clear [n]` - админская очистка последних сообщений
- `!очистить [n]` - русский алиас для очистки

## Настройка Discord

В Discord Developer Portal для бота должен быть включён:
- `MESSAGE CONTENT INTENT`

На сервере боту нужны права:
- `Send Messages`
- `Manage Messages`
- `Mention Everyone`

Для упрощения можно выдать `Administrator`.

## Переменные окружения

Обязательная переменная:

```bash
export DISCORD_TOKEN="your_discord_bot_token"
```

Необязательные:

```bash
export BOT_DB_PATH="bot-data.db"
export DAILY_CHANNEL_ID="123456789012345678"
```

## Конфиг в коде

Если хочешь настраивать всё прямо в проекте:

- ежедневное сообщение и базовая дата: `src/main/java/org/example/bot/ScheduledMessageConfig.java`
- текст для `!зов`: `src/main/java/org/example/bot/AdminCommandConfig.java`

Если `DAILY_CHANNEL_ID` не задан через окружение, бот возьмёт значение из `ScheduledMessageConfig`.

## Запуск

Сборка:

```bash
mvn clean package
```

Запуск собранного jar:

```bash
java -jar target/kojima_bot-1.0-SNAPSHOT-jar-with-dependencies.jar
```

Или можно просто запустить `org.example.Main` из IDE.

## Ограничения

- `!clear` может массово удалять только сообщения моложе 14 дней: это ограничение Discord API
- база сейчас локальная и хранится в SQLite

## Структура проекта

```text
src/main/java/org/example/
  Main.java
  bot/
    AdminCommandConfig.java
    CommandHandler.java
    DailyMessageScheduler.java
    MessageListener.java
    ScheduledMessageConfig.java
  db/
    MessageRepository.java
    StoredMessage.java
```

## Что можно добавить дальше

- slash-команды
- вынесение конфига в `.env` или `application.properties`
- Dockerfile
- деплой на VPS
- более подробное логирование
