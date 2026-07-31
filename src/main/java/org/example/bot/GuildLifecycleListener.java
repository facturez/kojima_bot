package org.example.bot;

import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.example.db.GuildConfigRepository;

import java.util.function.Consumer;

public final class GuildLifecycleListener extends ListenerAdapter {
    private final GuildConfigRepository configs;
    private final Consumer<String> activated;
    private final Consumer<String> deactivated;

    public GuildLifecycleListener(GuildConfigRepository configs, Consumer<String> activated,
                                  Consumer<String> deactivated) {
        this.configs = configs;
        this.activated = activated;
        this.deactivated = deactivated;
    }

    @Override public void onGuildJoin(GuildJoinEvent event) {
        configs.activateGuild(event.getGuild().getId(), event.getGuild().getName());
        activated.accept(event.getGuild().getId());
    }

    @Override public void onGuildLeave(GuildLeaveEvent event) {
        configs.deactivateGuild(event.getGuild().getId());
        deactivated.accept(event.getGuild().getId());
    }
}
