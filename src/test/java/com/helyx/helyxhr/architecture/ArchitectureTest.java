package com.helyx.helyxhr.architecture;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

  @Test
  void packages_haveNoCycles() {
    ArchRule rule = slices().matching("com.helyx.helyxhr.(*)..").should().beFreeOfCycles();
    rule.check(
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.helyx.helyxhr"));
  }
}
