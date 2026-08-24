package com.caderly.caderlyhr.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * CLAUDE.md §6 A03 asks for a CI check that fails on string-concatenated queries. Bytecode rules
 * cannot see this — the compiler turns {@code "..." + x} into an invokedynamic against
 * StringConcatFactory, indistinguishable from any other concatenation — so this reads the source.
 */
class NoSqlConcatenationTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    /**
     * Matches a query-building call whose argument list contains a {@code +}. Deliberately broad:
     * a false positive is a five-second conversation, a false negative is a SQL injection.
     */
    private static final Pattern CONCATENATED_QUERY =
            Pattern.compile(
                    "create(Native)?Query\\s*\\(\\s*[^;]*\"\\s*\\+|"
                            + "@Query\\s*\\(\\s*[^)]*\"\\s*\\+\\s*[A-Za-z_]");

    @Test
    void noSourceFile_buildsAQueryByStringConcatenation() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source, StandardCharsets.UTF_8);
                if (CONCATENATED_QUERY.matcher(content).find()) {
                    offenders.add(SOURCE_ROOT.relativize(source).toString());
                }
            }
        }

        assertThat(offenders)
                .as(
                        "Queries must be parameterised, never concatenated (CLAUDE.md §6 A03). "
                                + "Use a bind parameter instead of building the string.")
                .isEmpty();
    }

    @Test
    void theDetector_actuallyMatchesAConcatenatedQuery() {
        // A grep test that silently stops matching is worse than no test, so the pattern is
        // pinned against a sample of what it exists to catch.
        assertThat(CONCATENATED_QUERY.matcher("em.createQuery(\"from User where id = \" + id)").find())
                .isTrue();
        assertThat(
                        CONCATENATED_QUERY
                                .matcher("entityManager.createNativeQuery(\"select * from t where a = \" + a)")
                                .find())
                .isTrue();
        assertThat(CONCATENATED_QUERY.matcher("em.createQuery(\"from User where id = :id\")").find())
                .isFalse();
    }
}
