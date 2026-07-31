package org.example.db;

import java.util.Optional;

public record CallSettingsPatch(Optional<Boolean> enabled, Optional<String> messageText,
                                Optional<Integer> repeatCount) {
    public static CallSettingsPatch empty() {
        return new CallSettingsPatch(Optional.empty(), Optional.empty(), Optional.empty());
    }
}
