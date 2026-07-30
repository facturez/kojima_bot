package org.example.bot;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlashCommandDefinitionsTest {
    @Test
    void definesTheExpectedGlobalSlashCommands() {
        List<CommandData> definitions = SlashCommandDefinitions.all();

        assertEquals(
                Set.of("help", "ping", "stats", "last", "зов", "clear", "ban", "kick", "timeout"),
                definitions.stream().map(CommandData::getName).collect(java.util.stream.Collectors.toSet())
        );
        assertEquals(9, definitions.size());
        assertThrows(UnsupportedOperationException.class, definitions::clear);
    }

    @Test
    void definesAllOptionsWithTheirRequirednessAndBounds() {
        assertOption(command("last"), "count", OptionType.INTEGER, false);
        assertOption(command("clear"), "count", OptionType.INTEGER, false);
        assertOption(command("ban"), "user", OptionType.USER, true);
        assertOption(command("ban"), "reason", OptionType.STRING, false);
        assertOption(command("kick"), "user", OptionType.USER, true);
        assertOption(command("kick"), "reason", OptionType.STRING, false);
        assertOption(command("timeout"), "user", OptionType.USER, true);
        assertOption(command("timeout"), "duration", OptionType.STRING, true);
        assertOption(command("timeout"), "reason", OptionType.STRING, false);

        assertRange(command("last"), "count", 1, 20);
        assertRange(command("clear"), "count", 1, 100);
    }

    private SlashCommandData command(String name) {
        return SlashCommandDefinitions.all().stream()
                .filter(command -> command.getName().equals(name))
                .map(SlashCommandData.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private void assertOption(SlashCommandData command, String name, OptionType type, boolean required) {
        OptionData option = option(command, name);

        assertEquals(type, option.getType());
        assertEquals(required, option.isRequired());
    }

    private void assertRange(SlashCommandData command, String name, long minimum, long maximum) {
        OptionData option = option(command, name);

        assertEquals(minimum, option.getMinValue().longValue());
        assertEquals(maximum, option.getMaxValue().longValue());
    }

    private OptionData option(SlashCommandData command, String name) {
        return command.getOptions().stream()
                .filter(option -> option.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
