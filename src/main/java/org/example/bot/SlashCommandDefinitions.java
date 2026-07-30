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
                Commands.slash("deport", "Депортация из Кодзимы(бан)")
                        .addOptions(
                                new OptionData(OptionType.USER, "чел", "Участник для депортации", true),
                                new OptionData(OptionType.STRING, "причина", "Причина депортации", false)
                        ),
                Commands.slash("magadan", "Этап в магадан(кик)")
                        .addOptions(
                                new OptionData(OptionType.USER, "чел", "Участник для этапирования", true),
                                new OptionData(OptionType.STRING, "причина", "Причина этапировния", false)
                        ),
                Commands.slash("kpz", "Заключение в обезьянник(тайм-аут)")
                        .addOptions(
                                new OptionData(OptionType.USER, "чел", "Участник для заключения в обезьянник", true),
                                new OptionData(
                                        OptionType.STRING,
                                        "duration",
                                        "Длительность: 30m, 2h или 7d",
                                        true
                                ),
                                new OptionData(OptionType.STRING, "причина", "Причина заключения", false)
                        )
        );
    }
}
