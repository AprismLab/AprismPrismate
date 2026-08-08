package com.aprism.prismate.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PrismateConfig}: defaults, parsing, and graceful fallback on
 * broken input.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("PrismateConfig")
class PrismateConfigTest {

    @TempDir
    Path tempDir;

    private Path configFile() {
        return tempDir.resolve(PrismateConfig.CONFIG_RELATIVE_PATH);
    }

    @Test
    @DisplayName("returns defaults when no config file exists")
    void defaultsWhenAbsent() {
        PrismateConfig config = PrismateConfig.load(tempDir);
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getExtraAjeDirs()).isEmpty();
    }

    @Test
    @DisplayName("parses enabled=false")
    void parsesDisabled() throws Exception {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), "{\"enabled\": false}");
        PrismateConfig config = PrismateConfig.load(tempDir);
        assertThat(config.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("parses extraAjeDirs")
    void parsesExtraDirs() throws Exception {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(),
                "{\"enabled\": true, \"extraAjeDirs\": [\"extra-mods\", \"/abs/dir\"]}");
        PrismateConfig config = PrismateConfig.load(tempDir);
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getExtraAjeDirs()).containsExactly("extra-mods", "/abs/dir");
    }

    @Test
    @DisplayName("falls back to defaults on broken JSON")
    void brokenJsonFallsBack() throws Exception {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), "{ this is not valid json ");
        PrismateConfig config = PrismateConfig.load(tempDir);
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getExtraAjeDirs()).isEmpty();
    }

    @Test
    @DisplayName("falls back to defaults on an empty file")
    void emptyFileFallsBack() throws Exception {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), "");
        PrismateConfig config = PrismateConfig.load(tempDir);
        assertThat(config.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("tolerates a null extraAjeDirs field")
    void nullExtraDirsTolerated() throws Exception {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), "{\"enabled\": true, \"extraAjeDirs\": null}");
        PrismateConfig config = PrismateConfig.load(tempDir);
        assertThat(config.getExtraAjeDirs()).isEmpty();
    }
}
