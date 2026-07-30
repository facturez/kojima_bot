package org.example.bot;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.List;

public final class SlashCommandDefinitions {
    private SlashCommandDefinitions() {
    }

    public static List<CommandData> all() {
        return List.of(
                Commands.slash("help", "Показать список доступных команд"),
                Commands.slash("ping", "Проверить, что бот отвечает"),
                Commands.slash("stats", "Показать статистику архива сообщений"),
                Commands.slash("last", "Показать последние сообщения из канала")
                        .addOptions(new OptionData(
                                OptionType.INTEGER,
                                "count",
                                "Количество сообщений от 1 до 20",
                                false
                        ).setRequiredRange(1, 20)),
                Commands.slash("зов", "Позвать всех участников"),
                Commands.slash("clear", "Удалить последние сообщения")
                        .addOptions(new OptionData(
                                OptionType.INTEGER,
                                "count",
                                "Количество сообщений от 1 до 100",
                                false
                        ).setRequiredRange(1, 100)),
                Commands.slash("ban", "Забанить участника сервера")
                        .addOptions(
                                new OptionData(OptionType.USER, "user", "Участник для бана", true),
                                new OptionData(OptionType.STRING, "reason", "Причина бана", false)
                        ),
                Commands.slash("kick", "Исключить участника с сервера")
                        .addOptions(
                                new OptionData(OptionType.USER, "user", "Участник для исключения", true),
                                new OptionData(OptionType.STRING, "reason", "Причина исключения", false)
                        ),
                Commands.slash("timeout", "Выдать участнику тайм-аут")
                        .addOptions(
                                new OptionData(OptionType.USER, "user", "Участник для тайм-аута", true),
                                new OptionData(
                                        OptionType.STRING,
                                        "duration",
                                        "Длительность: 30m, 2h или 7d",
                                        true
                                ),
                                new OptionData(OptionType.STRING, "reason", "Причина тайм-аута", false)
                        )
        );
    }
}
