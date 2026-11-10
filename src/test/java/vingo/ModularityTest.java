package vingo;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Chặn module gọi vào {@code web} / {@code internal} của module khác và vòng phụ thuộc giữa module.
 */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(VingoApplication.class);

    @Test
    void modules_respectBoundaries() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        // Sinh sơ đồ module vào target/spring-modulith-docs để xem nhanh.
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
