/*
 * Copyright contributors to Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.evmtool;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class BuildTestSubCommandTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** An Amsterdam engine fixture with one self-transfer payload and its block access list. */
  private static final Path AMSTERDAM = resource("bal-self-transfer-engine.json");

  /** An Amsterdam engine fixture whose only payload is INVALID. */
  private static final Path INVALID = resource("bal-invalid-block-access-list-engine.json");

  /** A Cancun engine fixture whose payload carries a blob transaction, as payloads do: no blobs. */
  private static final Path CANCUN_BLOB = resource("build-test-blob.json");

  @TempDir Path tempDir;

  @Test
  void validPayloadIsBuiltAsTheFixtureHasIt() throws IOException {
    final JsonNode result = result(AMSTERDAM, 0);

    assertThat(result.get("pass").asBoolean()).isTrue();
    assertThat(result.get("built").asInt()).isEqualTo(1);
    assertThat(result.get("mismatches")).isEmpty();
    assertThat(result.get("rejections")).isEmpty();
    assertThat(result.get("lastPayloadStatus").asText()).isEqualTo("VALID");
  }

  @Test
  void blobTransactionWithoutItsBlobsIsBuiltBeforePrague() throws IOException {
    final JsonNode result = result(CANCUN_BLOB, 0);

    assertThat(result.get("pass").asBoolean()).isTrue();
    assertThat(result.get("built").asInt()).isEqualTo(1);
    assertThat(result.get("mismatches")).isEmpty();
  }

  @Test
  void invalidPayloadIsNeitherBuiltNorImported() throws IOException {
    final JsonNode result = result(INVALID, 0);

    assertThat(result.get("pass").asBoolean()).isTrue();
    assertThat(result.get("built").asInt()).isZero();
    assertThat(result.get("rejections")).isEmpty();
    assertThat(result.get("lastPayloadStatus").asText()).isEmpty();
  }

  @Test
  void builtPayloadThatDiffersIsAMismatch() throws IOException {
    // Besu builds this extra data into the block, which then hashes to another block than the
    // fixture names.
    final ObjectNode fixture = (ObjectNode) MAPPER.readTree(AMSTERDAM.toFile());
    final ObjectNode payload =
        (ObjectNode) fixture.elements().next().get("engineNewPayloads").get(0).get("params").get(0);
    final String blockHash = payload.get("blockHash").asText();
    payload.put("extraData", "0x01");
    final Path tampered = tempDir.resolve("tampered.json");
    MAPPER.writeValue(tampered.toFile(), fixture);

    final JsonNode result = result(tampered, 1);

    assertThat(result.get("pass").asBoolean()).isFalse();
    assertThat(result.get("built").asInt()).isEqualTo(1);
    final JsonNode mismatches = result.get("mismatches");
    assertThat(mismatches).hasSize(1);
    assertThat(mismatches.get(0).get("index").asInt()).isZero();
    assertThat(mismatches.get(0).get("field").asText()).isEqualTo("blockHash");
    assertThat(mismatches.get(0).get("expected").asText()).isEqualTo(blockHash);
    assertThat(mismatches.get(0).get("got").asText()).isNotEqualTo(blockHash);
  }

  @ParameterizedTest
  @ValueSource(strings = {"--bal-sequential", "--bal-report", "--outcome-only"})
  void takesNoneOfTheEngineTestFlags(final String flag) {
    final CommandLine commandLine = new CommandLine(new BuildTestSubCommand(null));

    assertThatThrownBy(() -> commandLine.parseArgs(flag, AMSTERDAM.toString()))
        .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
  }

  @Test
  void jsonValuesCompareAsValues() throws IOException {
    assertThat(equal("{\"a\": \"0xAB\", \"b\": null}", "{\"a\": \"0xab\"}")).isTrue();
    assertThat(equal("[\"0x01\", \"0x02\"]", "[\"0x01\", \"0x02\"]")).isTrue();
    assertThat(equal("[\"0x01\"]", "[\"0x01\", \"0x02\"]")).isFalse();
    assertThat(equal("{\"a\": \"0x01\"}", "{\"a\": \"0x02\"}")).isFalse();
    assertThat(equal("\"0x01\"", "null")).isFalse();
  }

  private static boolean equal(final String a, final String b) throws IOException {
    return FixturePayloadBuilder.jsonEquals(MAPPER.readTree(a), MAPPER.readTree(b));
  }

  /** Runs build-test on one fixture, checks its exit status and returns its only result. */
  private static JsonNode result(final Path fixture, final int exitCode) throws IOException {
    final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    final BuildTestSubCommand buildTest =
        new BuildTestSubCommand(
            new EvmToolCommand(System.in, new PrintWriter(stdout, true, UTF_8)));
    new CommandLine(buildTest).parseArgs("--json-array", fixture.toString());
    buildTest.run();

    assertThat(buildTest.getExitCode()).isEqualTo(exitCode);
    final JsonNode results = MAPPER.readTree(stdout.toString(UTF_8));
    assertThat(results).hasSize(1);
    return results.get(0);
  }

  private static Path resource(final String name) {
    return Path.of(BuildTestSubCommandTest.class.getResource(name).getPath());
  }
}
