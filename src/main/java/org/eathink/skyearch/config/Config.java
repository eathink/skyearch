package org.eathink.skyearch.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 全部关键参数的 YAML 配置。
 *
 * <pre>
 * server:
 *   host: 0.0.0.0
 *   port: 8080
 * storage:
 *   data-path: ./data
 * lucene:
 *   analyzer: smart-chinese
 *   ram-buffer-mb: 64
 *   refresh-interval-ms: 1000
 * search:
 *   default-limit: 20
 *   max-limit: 100
 * </pre>
 */
public record Config(
        Server server,
        Storage storage,
        Lucene lucene,
        Search search) {

    public record Server(
            @JsonProperty("host") String host,
            @JsonProperty("port") int port) {
    }

    public record Storage(
            @JsonProperty("data-path") String dataPath) {
    }

    public record Lucene(
            @JsonProperty("analyzer") String analyzer,
            @JsonProperty("ram-buffer-mb") int ramBufferMb,
            @JsonProperty("refresh-interval-ms") long refreshIntervalMs) {
    }

    public record Search(
            @JsonProperty("default-limit") int defaultLimit,
            @JsonProperty("max-limit") int maxLimit) {
    }

    public static Config load(Path file) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try {
            Config config = mapper.readValue(file.toFile(), Config.class);
            if (config == null) {
                throw new IllegalStateException("empty config file: " + file);
            }
            config.validate();
            return config;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read config file: " + file, e);
        }
    }

    /** 启动前校验配置,避免 NPE 散落在业务代码里。 */
    public void validate() {
        require(server != null && server.host() != null && !server.host().isBlank(), "server.host is required");
        require(server != null && server.port() >= 0 && server.port() < 65536, "server.port must be in [0, 65536)");
        require(storage != null && storage.dataPath() != null && !storage.dataPath().isBlank(), "storage.data-path is required");
        require(lucene != null, "lucene is required");
        String analyzer = lucene.analyzer() == null ? "" : lucene.analyzer().toLowerCase(Locale.ROOT);
        require("smart-chinese".equals(analyzer) || "standard".equals(analyzer),
                "lucene.analyzer must be one of: smart-chinese, standard");
        require(lucene.ramBufferMb() > 0, "lucene.ram-buffer-mb must be > 0");
        require(lucene.refreshIntervalMs() > 0, "lucene.refresh-interval-ms must be > 0");
        require(search != null && search.defaultLimit() > 0, "search.default-limit must be > 0");
        require(search != null && search.maxLimit() >= search.defaultLimit(), "search.max-limit must be >= search.default-limit");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("invalid config: " + message);
        }
    }
}
