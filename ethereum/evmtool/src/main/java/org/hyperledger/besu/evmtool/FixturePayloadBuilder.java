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

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.JsonRpcObjectMapperFactory;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequest;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcRequestException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.JsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.testing.TestingBuildBlockV1;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.core.encoding.EncodingContext;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.referencetests.EngineTestCaseSpec;

import java.util.Iterator;
import java.util.Optional;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * Builds a build test's payloads through Besu's own {@code testing_buildBlockV1} method, each on
 * the payload's parent and from its attributes, transactions and extra data, and compares every
 * field of the fixture's payload with the built one's.
 *
 * <p>The transactions are the payload's, so a blob transaction comes without its blobs: the method
 * decodes them as a block carries them, which only this runner does. The payload's gas limit is the
 * builder's target as {@code targetGasLimit} for every block: the method takes it on any fork, and
 * has no gas target of its own before Amsterdam.
 */
final class FixturePayloadBuilder {

  /**
   * The mapper the JSON-RPC server writes responses with, so a built payload is compared as sent.
   */
  private static final ObjectMapper RESPONSE_MAPPER =
      JsonRpcObjectMapperFactory.getResponseMapper();

  private final JsonRpcMethod method;
  private final ArrayNode mismatches = JsonNodeFactory.instance.arrayNode();
  private int built = 0;
  private int refused = 0;

  FixturePayloadBuilder(
      final ProtocolContext context,
      final ProtocolSchedule schedule,
      final EthScheduler scheduler) {
    // No transaction pool: a build test always names the block's transactions.
    this(
        new TestingBuildBlockV1(
            context,
            schedule,
            MiningConfiguration.newDefault(),
            null,
            scheduler,
            EncodingContext.BLOCK_BODY));
  }

  /**
   * Builds through {@code method}, the {@code testing_buildBlockV1} method or a stand-in for it.
   */
  FixturePayloadBuilder(final JsonRpcMethod method) {
    this.method = method;
  }

  /**
   * Builds payload {@code index}, records each field in which the built payload differs from it,
   * and records a build Besu refuses as one of {@code rejections}.
   */
  void build(
      final int index,
      final EngineTestCaseSpec.EngineNewPayload payload,
      final ArrayNode rejections) {
    final JsonNode want = payload.getParams()[0];

    final ObjectNode attributes = JsonNodeFactory.instance.objectNode();
    attributes.set("timestamp", want.get("timestamp"));
    attributes.set("prevRandao", want.get("prevRandao"));
    attributes.set("suggestedFeeRecipient", want.get("feeRecipient"));
    if (want.has("withdrawals")) {
      attributes.set("withdrawals", want.get("withdrawals"));
    }
    // The parent beacon block root is a newPayload param from V3 on.
    if (payload.getNewPayloadVersion() >= 3) {
      attributes.set("parentBeaconBlockRoot", payload.getParams()[2]);
    }
    // The slot number is an attribute from forkchoiceUpdatedV4 on. The gas limit target is too, and
    // the method also takes it before, where it otherwise targets the genesis gas limit.
    if (payload.getForkchoiceUpdatedVersion() >= 4) {
      attributes.set("slotNumber", want.get("slotNumber"));
    }
    attributes.set("targetGasLimit", want.get("gasLimit"));
    final String[] transactions = new String[want.get("transactions").size()];
    for (int i = 0; i < transactions.length; i++) {
      transactions[i] = want.get("transactions").get(i).asText();
    }
    final Object[] params = {
      want.get("parentHash").asText(), attributes, transactions, want.get("extraData").asText()
    };

    final JsonRpcResponse response;
    try {
      response =
          method.response(
              new JsonRpcRequestContext(new JsonRpcRequest("2.0", method.getName(), params)));
    } catch (final InvalidJsonRpcRequestException e) {
      // Over the wire, a param the method cannot read is a JSON-RPC error.
      refuse(rejections, index, new JsonRpcError(e.getRpcErrorType()));
      return;
    }
    if (response instanceof JsonRpcErrorResponse error) {
      refuse(rejections, index, error.getError());
      return;
    }
    built++;
    final JsonNode result =
        RESPONSE_MAPPER.valueToTree(((JsonRpcSuccessResponse) response).getResult());
    // Only the fixture's fields: before Amsterdam the method returns an ExecutionPayloadV3, so a
    // block before Cancun comes back with Cancun's fields, and the block hash already covers the
    // header Besu built.
    final JsonNode got = result.get("executionPayload");
    for (final Iterator<String> names = want.fieldNames(); names.hasNext(); ) {
      final String name = names.next();
      compare(index, name, want.get(name), got.get(name));
    }
    // The execution requests are a newPayload param from V4 on.
    if (payload.getNewPayloadVersion() >= 4) {
      compare(index, "executionRequests", payload.getParams()[3], result.get("executionRequests"));
    }
  }

  /** The number of payloads Besu built. */
  int built() {
    return built;
  }

  /** Every field in which a built payload differs from the fixture's, in fixture order. */
  ArrayNode mismatches() {
    return mismatches;
  }

  /** Why the test fails, or {@code null} when every payload built and matched. */
  @Nullable String failure() {
    if (mismatches.isEmpty() && refused == 0) {
      return null;
    }
    return String.format("%d mismatched fields, %d refused payloads", mismatches.size(), refused);
  }

  private void refuse(final ArrayNode rejections, final int index, final JsonRpcError error) {
    FixtureRunner.addRejection(
        rejections, index, Optional.empty(), EngineTestSubCommand.rpcError(error));
    refused++;
  }

  private void compare(
      final int index,
      final String field,
      final @Nullable JsonNode expected,
      final @Nullable JsonNode got) {
    if (!jsonEquals(expected, got)) {
      final ObjectNode mismatch = mismatches.addObject();
      mismatch.put("index", index);
      mismatch.put("field", field);
      mismatch.set("expected", expected == null ? NullNode.getInstance() : expected);
      mismatch.set("got", got == null ? NullNode.getInstance() : got);
    }
  }

  /**
   * Compares two JSON values as values rather than text: a missing field equals null, objects
   * compare by their fields in any order, and strings, which carry the payload's hex, compare
   * regardless of case.
   */
  static boolean jsonEquals(final @Nullable JsonNode a, final @Nullable JsonNode b) {
    if (a == null || a.isNull() || b == null || b.isNull()) {
      return (a == null || a.isNull()) && (b == null || b.isNull());
    }
    if (a.isObject() && b.isObject()) {
      final TreeSet<String> names = new TreeSet<>();
      a.fieldNames().forEachRemaining(names::add);
      b.fieldNames().forEachRemaining(names::add);
      return names.stream().allMatch(name -> jsonEquals(a.get(name), b.get(name)));
    }
    if (a.isArray() && b.isArray()) {
      if (a.size() != b.size()) {
        return false;
      }
      for (int i = 0; i < a.size(); i++) {
        if (!jsonEquals(a.get(i), b.get(i))) {
          return false;
        }
      }
      return true;
    }
    if (a.isTextual() && b.isTextual()) {
      return a.asText().equalsIgnoreCase(b.asText());
    }
    return a.equals(b);
  }
}
