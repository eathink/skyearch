package org.eathink.skyearch.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loadFullConfig() throws Exception {
        Path file = tempDir.resolve("application.yml");
        Files.writeString(file, """
                server:
                  host: 127.0.0.1
                  port: 9000
                storage:
                  data-path: ./data-test
                lucene:
                  analyzer: smart-chinese
                  ram-buffer-mb: 32
                  refresh-interval-ms: 500
                search:
                  default-limit: 10
                  max-limit: 50
                """, StandardOpenOption.CREATE);
        Config config = Config.load(file);
        config.validate();
        assertEquals("127.0.0.1", config.server().host());
        assertEquals(9000, config.server().port());
        assertEquals("./data-test", config.storage().dataPath());
        assertEquals("smart-chinese", config.lucene().analyzer());
        assertEquals(32, config.lucene().ramBufferMb());
        assertEquals(500, config.lucene().refreshIntervalMs());
        assertEquals(10, config.search().defaultLimit());
        assertEquals(50, config.search().maxLimit());
    }

    @Test
    void rejectInvalidPort() throws Exception {
        Path file = tempDir.resolve("bad.yml");
        Files.writeString(file, """
                server:
                  host: 0.0.0.0
                  port: -1
                storage:
                  data-path: ./data
                lucene:
                  analyzer: smart-chinese
                  ram-buffer-mb: 64
                  refresh-interval-ms: 1000
                search:
                  default-limit: 20
                  max-limit: 100
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Config.load(file));
        assertTrue(e.getMessage().contains("port"));
    }

    @Test
    void rejectUnknownAnalyzer() throws Exception {
        Path file = tempDir.resolve("bad2.yml");
        Files.writeString(file, """
                server:
                  host: 0.0.0.0
                  port: 8080
                storage:
                  data-path: ./data
                lucene:
                  analyzer: ik
                  ram-buffer-mb: 64
                  refresh-interval-ms: 1000
                search:
                  default-limit: 20
                  max-limit: 100
                """);
        assertThrows(IllegalStateException.class, () -> Config.load(file));
    }
}
