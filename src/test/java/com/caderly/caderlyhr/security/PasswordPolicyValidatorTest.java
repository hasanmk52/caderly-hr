package com.caderly.caderlyhr.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * PRD §19.1's password rules. Driven through a real Bean Validation {@code Validator} rather than
 * by calling the validator class directly, so the {@link ValidPassword} wiring is covered too — a
 * constraint that is never discovered fails open, and a unit test of the class alone would not
 * notice.
 */
class PasswordPolicyValidatorTest {

    private record Candidate(@ValidPassword String password) {}

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Set<ConstraintViolation<Candidate>> validate(String password) {
        return validator.validate(new Candidate(password));
    }

    @Test
    void isValid_whenAllRulesMet_passes() {
        assertThat(validate("Str0ngPassphrase")).isEmpty();
    }

    @Test
    void isValid_atExactlyTenCharacters_passes() {
        // The boundary, separately from the general happy path: >= vs > here is a real off-by-one.
        assertThat(validate("Passw0rdAb")).isEmpty();
    }

    /**
     * Each rule is asserted with its exact message, not merely "rejected". The message is the
     * feature — {@code PasswordPolicyValidator} replaces the default constraint message with the
     * specific rule that failed so the user is not left guessing, and a generic assertion would
     * pass even if that mapping broke and every failure said the same thing.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "Sh0rtPas,         Password must be at least 10 characters",
        "str0ngpassphrase, Password must contain an upper-case letter",
        "STR0NGPASSPHRASE, Password must contain a lower-case letter",
        "StrongPassphrase, Password must contain a digit"
    })
    void isValid_whenARuleIsBroken_reportsThatSpecificRule(String password, String expected) {
        assertThat(validate(password))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getMessage()).isEqualTo(expected));
    }

    @Test
    void isValid_whenNull_fails() {
        // Not @NotBlank's job to cover this: the field carries @ValidPassword on its own in
        // AuthForms.SetPassword, so a null must fail here or an empty password would slip through.
        assertThat(validate(null)).isNotEmpty();
    }
}
