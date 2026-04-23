package org.example.bot;

import java.util.List;

public final class AdminCommandConfig {
    public static final int CALL_REPEAT_COUNT = 3;
    public static final String CALL_MESSAGE_PLACEHOLDER = "VSTAV_TEKST_DLYA_KOMANDY_ZOV";
    public static final List<String> CALL_ALLOWED_ROLE_NAMES = List.of("управленец");
    public static final List<String> CALL_ALLOWED_ROLE_IDS = List.of();

    public static final String CALL_MESSAGE_TEXT = """
            Братва общий сбор на FACEIT
            """;

    private AdminCommandConfig() {
    }
}
