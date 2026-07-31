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
                Set.of("help", "ping", "stats", "last", "зов", "clear", "deport", "magadan", "kpz", "setup", "config"),
                definitions.stream().map(CommandData::getName).collect(java.util.stream.Collectors.toSet())
        );
        assertEquals(11, definitions.size());
        assertThrows(UnsupportedOperationException.class, definitions::clear);
    }

    @Test
    void definesAllOptionsWithTheirRequirednessAndBounds() {
        assertOption(command("last"), "count", OptionType.INTEGER, false);
        assertOption(command("clear"), "count", OptionType.INTEGER, false);
        assertOption(command("deport"), "чел", OptionType.USER, true);
        assertOption(command("deport"), "причина", OptionType.STRING, false);
        assertOption(command("magadan"), "чел", OptionType.USER, true);
        assertOption(command("magadan"), "причина", OptionType.STRING, false);
        assertOption(command("kpz"), "чел", OptionType.USER, true);
        assertOption(command("kpz"), "срок", OptionType.STRING, true);
        assertOption(command("kpz"), "причина", OptionType.STRING, false);

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
