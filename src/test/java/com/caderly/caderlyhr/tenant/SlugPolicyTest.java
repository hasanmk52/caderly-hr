package com.caderly.caderlyhr.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.tenant.SlugPolicy.Problem;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** ADR 0022 decision D: which workspace addresses self-serve sign-up accepts. */
class SlugPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"acme", "acme-hr", "a1b", "mhz-group-2026", "Acme", "  acme  "})
    void checkSelfServe_whenAcceptable_returnsEmpty(String slug) {
        assertThat(SlugPolicy.checkSelfServe(slug)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "'', REQUIRED",
        "'   ', REQUIRED",
        "ab, TOO_SHORT",
        "-acme, INVALID_FORMAT",
        "acme-, INVALID_FORMAT",
        "ac me, INVALID_FORMAT",
        "acme.co, INVALID_FORMAT",
        "www, RESERVED",
        "admin, RESERVED",
        "superadmin, RESERVED",
        "WWW, RESERVED"
    })
    void checkSelfServe_whenNotAcceptable_namesTheFirstRuleBroken(String slug, Problem expected) {
        assertThat(SlugPolicy.checkSelfServe(slug)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {51, 60})
    void checkSelfServe_whenLongerThanTheColumn_isInvalid(int length) {
        assertThat(SlugPolicy.checkSelfServe("a".repeat(length))).contains(Problem.INVALID_FORMAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"www", "Admin", " api "})
    void isReserved_ignoresCaseAndSurroundingSpace(String slug) {
        assertThat(SlugPolicy.isReserved(slug)).isTrue();
    }
}
