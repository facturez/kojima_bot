package org.example.bot;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class SetupValidationTest {
    @Test void parsesTypedValues() {
        assertEquals(ZoneId.of("Europe/Berlin"), SetupValidation.parseZone("Europe/Berlin"));
        assertEquals(LocalDate.of(2026, 7, 31), SetupValidation.parseDate("2026-07-31"));
    }

    @Test void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> SetupValidation.parseZone("Mars/Kojima"));
        assertThrows(IllegalArgumentException.class, () -> SetupValidation.parseDate("31.07.2026"));
        assertThrows(IllegalArgumentException.class, () -> SetupValidation.nonblank("  ", "Текст"));
    }

    @Test void setupRequiresManageServerOrAdministrator() {
        assertTrue(SetupCommandHandler.canSetup(true, false));
        assertTrue(SetupCommandHandler.canSetup(false, true));
        assertFalse(SetupCommandHandler.canSetup(false, false));
    }
}
