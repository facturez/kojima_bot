package org.example.bot;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.example.db.CallSettingsPatch;
import org.example.db.DailySettingsPatch;
import org.example.db.GuildConfig;
import org.example.db.GuildConfigRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.function.Consumer;

public final class SetupCommandHandler {
    private final GuildConfigRepository configs;
    private final Consumer<String> refreshDaily;

    public SetupCommandHandler(GuildConfigRepository configs, Consumer<String> refreshDaily) {
        this.configs = configs;
        this.refreshDaily = refreshDaily;
    }

    public void handle(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) { ephemeral(event, "Эта команда работает только на сервере."); return; }
        if (event.getName().equals("config")) {
            try { showConfig(event); }
            catch (RuntimeException failure) { ephemeral(event, "Не удалось прочитать конфигурацию сервера."); }
            return;
        }
        Member member = event.getMember();
        if (member == null || !canSetup(member.hasPermission(Permission.MANAGE_SERVER),
                member.hasPermission(Permission.ADMINISTRATOR))) {
            ephemeral(event, "Нужно право Manage Server или Administrator.");
            return;
        }
        try {
            switch (event.getSubcommandName()) {
                case "daily" -> updateDaily(event);
                case "call" -> updateCall(event);
                case "archive" -> updateArchive(event);
                default -> throw new IllegalArgumentException("Неизвестная подкоманда setup.");
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            ephemeral(event, failure.getMessage());
        }
    }

    private void updateDaily(SlashCommandInteractionEvent event) {
        Optional<Boolean> enabled = option(event, "enabled", OptionMapping::getAsBoolean);
        Optional<String> channel = option(event, "channel", option -> {
            GuildChannel selected = option.getAsChannel().asTextChannel();
            if (!selected.getGuild().getId().equals(event.getGuild().getId())) throw new IllegalArgumentException("Канал должен принадлежать этому серверу.");
            return selected.getId();
        });
        Optional<ZoneId> timezone = option(event, "timezone", o -> SetupValidation.parseZone(o.getAsString()));
        Optional<String> prefix = option(event, "message_prefix", o -> SetupValidation.nonblank(o.getAsString(), "Префикс"));
        Optional<LocalDate> date = option(event, "base_date", o -> SetupValidation.parseDate(o.getAsString()));
        Optional<Integer> number = option(event, "base_day_number", OptionMapping::getAsInt);
        if (enabled.isEmpty() && channel.isEmpty() && timezone.isEmpty() && prefix.isEmpty() && date.isEmpty() && number.isEmpty())
            throw new IllegalArgumentException("Укажи хотя бы одну настройку.");
        String guildId = event.getGuild().getId();
        configs.updateDaily(guildId, new DailySettingsPatch(enabled, channel, timezone, prefix, date, number));
        refreshDaily.accept(guildId);
        ephemeral(event, "Настройки daily обновлены.");
    }

    private void updateCall(SlashCommandInteractionEvent event) {
        Optional<Boolean> enabled = option(event, "enabled", OptionMapping::getAsBoolean);
        Optional<String> message = option(event, "message", o -> SetupValidation.nonblank(o.getAsString(), "Сообщение"));
        Optional<Integer> repeat = option(event, "repeat_count", OptionMapping::getAsInt);
        OptionMapping roleOption = event.getOption("allowed_role");
        OptionMapping actionOption = event.getOption("role_action");
        String action = actionOption == null ? (roleOption == null ? null : "add") : actionOption.getAsString();
        if (enabled.isEmpty() && message.isEmpty() && repeat.isEmpty() && roleOption == null && action == null)
            throw new IllegalArgumentException("Укажи хотя бы одну настройку.");
        String guildId = event.getGuild().getId();
        if ("clear".equals(action)) {
            if (roleOption != null) throw new IllegalArgumentException("Для clear роль указывать не нужно.");
        } else if (action != null) {
            if (roleOption == null) throw new IllegalArgumentException("Для add/remove укажи allowed_role.");
            Role role = roleOption.getAsRole();
            if (!role.getGuild().getId().equals(guildId)) throw new IllegalArgumentException("Роль должна принадлежать этому серверу.");
            if (!action.equals("add") && !action.equals("remove")) throw new IllegalArgumentException("Неизвестное действие над ролью.");
        }
        configs.updateCall(guildId, new CallSettingsPatch(enabled, message, repeat));
        if ("clear".equals(action)) {
            configs.clearAllowedRoles(guildId);
        } else if (action != null) {
            Role role = roleOption.getAsRole();
            if (action.equals("add")) configs.addAllowedRole(guildId, role.getId());
            else configs.removeAllowedRole(guildId, role.getId());
        }
        ephemeral(event, "Настройки call обновлены.");
    }

    private void updateArchive(SlashCommandInteractionEvent event) {
        OptionMapping enabled = event.getOption("enabled");
        if (enabled == null) throw new IllegalArgumentException("Укажи enabled.");
        configs.setArchiveEnabled(event.getGuild().getId(), enabled.getAsBoolean());
        ephemeral(event, "Настройки archive обновлены.");
    }

    private void showConfig(SlashCommandInteractionEvent event) {
        GuildConfig config = configs.requireConfig(event.getGuild().getId());
        ephemeral(event, """
                Конфигурация сервера:
                Archive: %s
                Daily: %s, channel=%s, timezone=%s, prefix=%s, base=%s/%d
                Call: %s, repeats=%d, roles=%s, message=%s
                """.formatted(config.archiveEnabled(), config.daily().enabled(), config.daily().channelId(),
                config.daily().timezone(), config.daily().messagePrefix(), config.daily().baseDate(),
                config.daily().baseDayNumber(), config.call().enabled(), config.call().repeatCount(),
                config.call().allowedRoleIds(), config.call().messageText()));
    }

    private static <T> Optional<T> option(SlashCommandInteractionEvent event, String name,
                                           java.util.function.Function<OptionMapping,T> mapper) {
        OptionMapping option = event.getOption(name);
        return option == null ? Optional.empty() : Optional.of(mapper.apply(option));
    }

    private static void ephemeral(SlashCommandInteractionEvent event, String text) {
        event.reply(text == null || text.isBlank() ? "Не удалось обновить конфигурацию." : text).setEphemeral(true).queue();
    }

    static boolean canSetup(boolean manageServer, boolean administrator) {
        return manageServer || administrator;
    }
}
