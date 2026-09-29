package io.muleshield.core.payee;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.muleshield.core.payee.NameMatcher.Result;

class NameMatcherTest {

    @Test
    void ignoresTitlesOrderPunctuationAndAccents() {
        assertThat(NameMatcher.check("Dr. Asha K. Rao", "RAO ASHA K").result()).isEqualTo(Result.MATCH);
        assertThat(NameMatcher.check("José Álvarez", "JOSE ALVAREZ").result()).isEqualTo(Result.MATCH);
        assertThat(NameMatcher.check("Shri Ramesh Kumar", "Ramesh Kumar").result()).isEqualTo(Result.MATCH);
    }

    @Test
    void aTypoOrAnInitialIsCloseAndShowsTheRealName() {
        var check = NameMatcher.check("Asha Rau", "Asha Rao");
        assertThat(check.result()).isEqualTo(Result.CLOSE_MATCH);
        assertThat(check.registeredName()).isEqualTo("Asha Rao");
        assertThat(NameMatcher.check("A Rao", "Asha Rao").result()).isEqualTo(Result.CLOSE_MATCH);
    }

    @Test
    void aDifferentPersonIsNoMatch() {
        var check = NameMatcher.check("Asha Rao", "Vikram Traders Pvt Ltd");
        assertThat(check.result()).isEqualTo(Result.NO_MATCH);
        assertThat(check.mismatch()).isEqualTo(1.0);
    }

    @Test
    void missingNameCannotBeChecked() {
        assertThat(NameMatcher.check("", "Asha Rao").result()).isEqualTo(Result.NOT_POSSIBLE);
    }
}
