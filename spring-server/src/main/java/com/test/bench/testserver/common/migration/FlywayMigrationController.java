package com.test.bench.testserver.common.migration;

import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/migration")
@RequiredArgsConstructor
public class FlywayMigrationController {

    private final Flyway flyway;

    @PostMapping("/up")
    public ResponseEntity<String> up() {
        MigrateResult result = flyway.migrate();
        return ResponseEntity.ok("Applied " + result.migrationsExecuted + " migration(s). Target version: " + result.targetSchemaVersion);
    }

    @PostMapping("/down")
    public ResponseEntity<String> down() {
        flyway.clean();
        return ResponseEntity.ok("Schema cleaned successfully");
    }
}
