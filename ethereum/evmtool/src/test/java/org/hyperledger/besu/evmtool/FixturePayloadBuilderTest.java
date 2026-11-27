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

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.JsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.referencetests.EngineTestCaseSpec;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** The build step on its own, against a stand-in for {@code testing_buildBlockV1}. */
class FixturePayloadBuilderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A payload as a fixture carries it, sent with newPayloadV1 and forkchoiceUpdatedV1. */
  private static final String PAYLOAD =
      "{\"parentHash\": \"0x01\", \"feeRecipient\": \"0x02\", \"stateRoot\": \"0x03\","
          + " \"timestamp\": \"0xc\", \"prevRandao\": \"0x04\", \"gasLimit\": \"0x1c9c380\","
          + " \"transactions\": [], \"extraData\": \"0x\"}";

  @Test
  void refusedBuildIsARejectionAndFailsTheFixture() throws Exception {
    final FixturePayloadBuilder builder =
        new FixturePayloadBuilder(
            method(
                request ->
                    new JsonRpcErrorResponse(null, new JsonRpcError(-32000, "refused", null))));
    final ArrayNode rejections = JsonNodeFactory.instance.arrayNode();

    builder.build(0, payload(), rejections);

    assertThat(builder.built()).isZero();
    assertThat(builder.mismatches()).isEmpty();
    assertThat(rejections).hasSize(1);
    assertThat(rejections.get(0).get("index").asInt()).isZero();
    assertThat(rejections.get(0).get("error").asText()).isEqualTo("-32000: refused");
    assertThat(builder.failure()).isEqualTo("0 mismatched fields, 1 refused payloads");
  }

  @Test
  void differingFieldIsAMismatchAndFailsTheFixture() throws Exception {
    final ObjectNode built = (ObjectNode) MAPPER.readTree(PAYLOAD);
    built.put("stateRoot", "0x05");
    final FixturePayloadBuilder builder =
        new FixturePayloadBuilder(
            method(request -> new JsonRpcSuccessResponse(null, Map.of("executionPayload", built))));
    final ArrayNode rejections = JsonNodeFactory.instance.arrayNode();

    builder.build(0, payload(), rejections);

    assertThat(builder.built()).isEqualTo(1);
    assertThat(rejections).isEmpty();
    assertThat(builder.mismatches()).hasSize(1);
    final JsonNode mismatch = builder.mismatches().get(0);
    assertThat(mismatch.get("field").asText()).isEqualTo("stateRoot");
    assertThat(mismatch.get("expected").asText()).isEqualTo("0x03");
    assertThat(mismatch.get("got").asText()).isEqualTo("0x05");
    assertThat(builder.failure()).isEqualTo("1 mismatched fields, 0 refused payloads");
  }

  @Test
  void gasLimitIsTheTargetBeforeAmsterdamToo() throws Exception {
    final AtomicReference<Object> attributes = new AtomicReference<>();
    final FixturePayloadBuilder builder =
        new FixturePayloadBuilder(
            method(
                request -> {
                  attributes.set(request.getRequest().getParams()[1]);
                  return new JsonRpcErrorResponse(null, new JsonRpcError(-32000, "refused", null));
                }));

    builder.build(0, payload(), JsonNodeFactory.instance.arrayNode());

    final ObjectNode sent = (ObjectNode) attributes.get();
    assertThat(sent.get("targetGasLimit").asText()).isEqualTo("0x1c9c380");
    assertThat(sent.has("slotNumber")).isFalse();
  }

  private static EngineTestCaseSpec.EngineNewPayload payload() throws Exception {
    return new EngineTestCaseSpec.EngineNewPayload(
        new JsonNode[] {MAPPER.readTree(PAYLOAD)}, "1", "1", null, null);
  }

  private static JsonRpcMethod method(
      final Function<JsonRpcRequestContext, JsonRpcResponse> response) {
    return new JsonRpcMethod() {
      @Override
      public String getName() {
        return "testing_buildBlockV1";
      }

      @Override
      public JsonRpcResponse response(final JsonRpcRequestContext request) {
        return response.apply(request);
      }
    };
  }
}
