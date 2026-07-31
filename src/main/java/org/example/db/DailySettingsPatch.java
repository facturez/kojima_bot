package org.example.db;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

public record DailySettingsPatch(Optional<Boolean> enabled, Optional<String> channelId, Optional<ZoneId> timezone,
                                 Optional<String> messagePrefix, Optional<LocalDate> baseDate,
                                 Optional<Integer> baseDayNumber) {
    public static DailySettingsPatch empty() {
        return new DailySettingsPatch(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
