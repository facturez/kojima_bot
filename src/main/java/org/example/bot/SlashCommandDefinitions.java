package org.example.bot;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

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
                Commands.slash(SlashCommandContract.DEPORT, "Депортация из Кодзимы(бан)")
                        .addOptions(
                                new OptionData(
                                        OptionType.USER,
                                        SlashCommandContract.TARGET_OPTION,
                                        "Участник для депортации",
                                        true
                                ),
                                new OptionData(
                                        OptionType.STRING,
                                        SlashCommandContract.REASON_OPTION,
                                        "Причина депортации",
                                        false
                                )
                        ),
                Commands.slash(SlashCommandContract.MAGADAN, "Этап в магадан(кик)")
                        .addOptions(
                                new OptionData(
                                        OptionType.USER,
                                        SlashCommandContract.TARGET_OPTION,
                                        "Участник для этапирования",
                                        true
                                ),
                                new OptionData(
                                        OptionType.STRING,
                                        SlashCommandContract.REASON_OPTION,
                                        "Причина этапировния",
                                        false
                                )
                        ),
                Commands.slash(SlashCommandContract.KPZ, "Заключение в обезьянник(тайм-аут)")
                        .addOptions(
                                new OptionData(
                                        OptionType.USER,
                                        SlashCommandContract.TARGET_OPTION,
                                        "Участник для заключения в обезьянник",
                                        true
                                ),
                                new OptionData(
                                        OptionType.STRING,
                                        SlashCommandContract.DURATION_OPTION,
                                        "Длительность: 30m, 2h или 7d",
                                        true
                                ),
                                new OptionData(
                                        OptionType.STRING,
                                        SlashCommandContract.REASON_OPTION,
                                        "Причина заключения",
                                        false
                                )
                        ),
                Commands.slash("setup", "Настроить Kojima Bot для этого сервера")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                        .setGuildOnly(true)
                        .addSubcommands(
                                new SubcommandData("daily", "Настроить ежедневное сообщение").addOptions(
                                        new OptionData(OptionType.BOOLEAN, "enabled", "Включить или выключить", false),
                                        new OptionData(OptionType.CHANNEL, "channel", "Канал ежедневного сообщения", false)
                                                .setChannelTypes(ChannelType.TEXT),
                                        new OptionData(OptionType.STRING, "timezone", "IANA timezone, например Europe/Moscow", false),
                                        new OptionData(OptionType.STRING, "message_prefix", "Префикс сообщения", false),
                                        new OptionData(OptionType.STRING, "base_date", "Базовая дата YYYY-MM-DD", false),
                                        new OptionData(OptionType.INTEGER, "base_day_number", "Номер дня на базовую дату", false)
                                ),
                                new SubcommandData("call", "Настроить команду зов").addOptions(
                                        new OptionData(OptionType.BOOLEAN, "enabled", "Включить или выключить", false),
                                        new OptionData(OptionType.STRING, "message", "Текст призыва", false),
                                        new OptionData(OptionType.INTEGER, "repeat_count", "Количество повторов от 1 до 10", false).setRequiredRange(1, 10),
                                        new OptionData(OptionType.ROLE, "allowed_role", "Разрешённая роль", false),
                                        new OptionData(OptionType.STRING, "role_action", "Действие над ролью", false)
                                                .addChoice("Добавить", "add").addChoice("Удалить", "remove").addChoice("Очистить все", "clear")
                                ),
                                new SubcommandData("archive", "Настроить архив сообщений").addOptions(
                                        new OptionData(OptionType.BOOLEAN, "enabled", "Включить или выключить архив", true)
                                )
                        ),
                Commands.slash("config", "Показать конфигурацию этого сервера").setGuildOnly(true)
        );
    }
}
