package org.example.bot;

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandHandlerTest {
    @Test
    void queuesDiscordMessageWithExplicitFailureHandler() {
        AtomicReference<String> sentContent = new AtomicReference<>();
        AtomicReference<Consumer<Throwable>> failureHandler = new AtomicReference<>();
        MessageCreateAction action = (MessageCreateAction) Proxy.newProxyInstance(
                MessageCreateAction.class.getClassLoader(),
                new Class<?>[]{MessageCreateAction.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("queue") && method.getParameterCount() == 2) {
                        @SuppressWarnings("unchecked")
                        Consumer<Throwable> handler = (Consumer<Throwable>) arguments[1];
                        failureHandler.set(handler);
                    }
                    return null;
                }
        );
        MessageChannel channel = (MessageChannel) Proxy.newProxyInstance(
                MessageChannel.class.getClassLoader(),
                new Class<?>[]{MessageChannel.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("sendMessage")) {
                        sentContent.set(arguments[0].toString());
                        return action;
                    }
                    return null;
                }
        );

        CommandHandler.sendMessage(channel, "hello Discord");

        assertEquals("hello Discord", sentContent.get());
        assertNotNull(failureHandler.get());

        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
        PrintStream originalError = System.err;
        try {
            System.setErr(new PrintStream(errorOutput));
            failureHandler.get().accept(new IllegalStateException("network unavailable"));
        } finally {
            System.setErr(originalError);
        }

        assertTrue(errorOutput.toString().contains("Failed to send Discord message: network unavailable"));
    }
}
