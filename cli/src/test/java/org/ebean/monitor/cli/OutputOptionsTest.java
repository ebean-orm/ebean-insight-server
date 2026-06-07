package org.ebean.monitor.cli;

import org.ebean.monitor.v1.model.App;
import org.ebean.monitor.v1.model.Env;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutputOptionsTest {

  private static String capture(Runnable action) {
    PrintStream original = System.out;
    var buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setOut(original);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  @Test
  void defaultFormat_isText() {
    var out = new OutputOptions();
    assertThat(out.json()).isFalse();
  }

  @Test
  void jsonFlag_selectsJson() {
    var out = new OutputOptions();
    out.format = OutputOptions.Format.json;
    assertThat(out.json()).isTrue();
  }

  @Test
  void printJson_single_usesGeneratedAdapter() {
    var out = new OutputOptions();
    String json = capture(() -> out.printJson(App.class, new App(7L, "ebean-insight")));
    assertThat(json)
        .contains("\"id\":7")
        .contains("\"name\":\"ebean-insight\"");
  }

  @Test
  void printJsonList_rendersArray() {
    var out = new OutputOptions();
    String json = capture(() ->
        out.printJsonList(Env.class, List.of(new Env("dev"), new Env("prod"))));
    assertThat(json.trim()).startsWith("[").endsWith("]");
    assertThat(json).contains("\"name\":\"dev\"").contains("\"name\":\"prod\"");
  }

  @Test
  void printJsonList_empty_rendersEmptyArray() {
    var out = new OutputOptions();
    String json = capture(() -> out.printJsonList(Env.class, List.of()));
    assertThat(json.trim()).isEqualTo("[]");
  }

  @Test
  void resolve_readsJsonFromConfig() throws Exception {
    var configFile = java.nio.file.Files.createTempFile("insight-out", ".properties");
    java.nio.file.Files.writeString(configFile, "output=json\n");
    try {
      var out = new OutputOptions();
      out.resolve(new InsightConfig(configFile));
      assertThat(out.json()).isTrue();
    } finally {
      java.nio.file.Files.deleteIfExists(configFile);
    }
  }

  @Test
  void resolve_flagOverridesConfig() throws Exception {
    var configFile = java.nio.file.Files.createTempFile("insight-out", ".properties");
    java.nio.file.Files.writeString(configFile, "output=json\n");
    try {
      var out = new OutputOptions();
      out.format = OutputOptions.Format.text;
      out.resolve(new InsightConfig(configFile));
      assertThat(out.json()).isFalse();
    } finally {
      java.nio.file.Files.deleteIfExists(configFile);
    }
  }

  @Test
  void resolve_invalidConfigValue_throws() throws Exception {
    var configFile = java.nio.file.Files.createTempFile("insight-out", ".properties");
    java.nio.file.Files.writeString(configFile, "output=yaml\n");
    try {
      var out = new OutputOptions();
      assertThat(org.assertj.core.api.Assertions.catchThrowable(
          () -> out.resolve(new InsightConfig(configFile))))
          .isInstanceOf(CliException.class)
          .hasMessageContaining("Invalid output format");
    } finally {
      java.nio.file.Files.deleteIfExists(configFile);
    }
  }
}
