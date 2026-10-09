package it.cryptocensus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OwnOptionTest {

    @Test
    void theUsersOwnArgLineOptionSurvivesComposition() {
        assertEquals("kept", System.getProperty("my.own.option"));
    }
}
