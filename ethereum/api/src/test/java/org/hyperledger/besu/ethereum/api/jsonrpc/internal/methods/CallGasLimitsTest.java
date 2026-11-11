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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.config.GenesisConfig;
import org.hyperledger.besu.ethereum.api.ApiConfiguration;
import org.hyperledger.besu.ethereum.api.ImmutableApiConfiguration;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequest;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.core.ExecutionContextTestFixture;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.transaction.TransactionSimulator;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs eth_call, eth_estimateGas and eth_simulateV1 against a real simulator on an Amsterdam chain
 * and checks how they size and limit the gas of a call.
 */
public class CallGasLimitsTest {

  // an Amsterdam genesis with the system contracts that block simulation calls, and SENDER funded
  private static final String GENESIS_RESOURCE =
      "/org/hyperledger/besu/ethereum/api/jsonrpc/testing_buildBlockV1/chain-data/genesis.json";
  private static final String SENDER = "0x627306090abab3a6e1400e9345bc60c78a8bef57";
  private static final long TX_MAX_GAS_LIMIT = 1L << 24;
  // the block gas limit of the genesis block, above TX_MAX_GAS_LIMIT
  private static final long BLOCK_GAS_LIMIT = 30_000_000L;

  // Loops 3000 times, about 78,000 gas:
  //   PUSH2 3000 JUMPDEST PUSH1 1 SWAP1 SUB DUP1 PUSH1 3 JUMPI STOP
  private static final String BURNER = "0x000000000000000000000000000000000000b001";
  private static final String BURNER_CODE = "0x610bb85b600190038060035700";
  // Calls the burner with all gas left and returns whether it succeeded:
  //   PUSH1 0 x5 PUSH20 burner GAS CALL PUSH1 0 MSTORE PUSH1 32 PUSH1 0 RETURN
  private static final String CATCHER = "0x000000000000000000000000000000000000ca01";
  private static final String CATCHER_CODE =
      "0x6000600060006000600073" + BURNER.substring(2) + "5af160005260206000f3";
  // Calls the burner with 1,000 gas, which always runs out, and returns whether it succeeded:
  //   PUSH1 0 x5 PUSH20 burner PUSH2 1000 CALL PUSH1 0 MSTORE PUSH1 32 PUSH1 0 RETURN
  private static final String PROBER = "0x000000000000000000000000000000000000ca02";
  private static final String PROBER_CODE =
      "0x6000600060006000600073" + BURNER.substring(2) + "6103e8f160005260206000f3";
  // Sets a fresh storage slot: PUSH1 1 PUSH1 0 SSTORE STOP
  private static final String STORER = "0x000000000000000000000000000000000000b002";
  private static final String STORER_CODE = "0x600160005500";
  // Calls the storer with 30,000 gas, too little for the new slot's state gas unless a reservoir
  // pays it, and returns whether it succeeded:
  //   PUSH1 0 x5 PUSH20 storer PUSH2 30000 CALL PUSH1 0 MSTORE PUSH1 32 PUSH1 0 RETURN
  private static final String STORE_CATCHER = "0x000000000000000000000000000000000000ca03";
  private static final String STORE_CATCHER_CODE =
      "0x6000600060006000600073" + STORER.substring(2) + "617530f160005260206000f3";

  private final ObjectMapper mapper = new ObjectMapper();

  private ExecutionContextTestFixture fixture;
  private BlockchainQueries blockchainQueries;

  @BeforeEach
  public void setUp() throws Exception {
    final ObjectNode genesis =
        (ObjectNode) mapper.readTree(CallGasLimitsTest.class.getResource(GENESIS_RESOURCE));
    ((ObjectNode) genesis.get("config")).put("amsterdamTime", 0);
    final ObjectNode alloc = (ObjectNode) genesis.get("alloc");
    putCode(alloc, BURNER, BURNER_CODE);
    putCode(alloc, CATCHER, CATCHER_CODE);
    putCode(alloc, PROBER, PROBER_CODE);
    putCode(alloc, STORER, STORER_CODE);
    putCode(alloc, STORE_CATCHER, STORE_CATCHER_CODE);
    fixture =
        ExecutionContextTestFixture.builder(GenesisConfig.fromConfig(genesis))
            .dataStorageFormat(DataStorageFormat.BONSAI)
            .build();
    blockchainQueries =
        new BlockchainQueries(
            fixture.getProtocolSchedule(),
            fixture.getBlockchain(),
            fixture.getStateArchive(),
            MiningConfiguration.MINING_DISABLED);
    assertThat(fixture.getGenesis().getHeader().getGasLimit()).isEqualTo(BLOCK_GAS_LIMIT);
  }

  @Test
  public void estimateLeavesACaughtNestedCallEnoughGas() throws Exception {
    final long gas = estimate(ApiConfiguration.DEFAULT_GAS_CAP, call(CATCHER));

    final Object output =
        success(
            ethCall(ApiConfiguration.DEFAULT_GAS_CAP), "eth_call", atLatest(call(CATCHER, gas)));
    assertThat(word(output)).as("nested call succeeds at the estimate %d", gas).isEqualTo(1);
  }

  @Test
  public void estimateIsNotInflatedByANestedCallThatAlwaysFails() throws Exception {
    final long gas = estimate(ApiConfiguration.DEFAULT_GAS_CAP, call(PROBER));

    assertThat(gas).isLessThan(100_000L);
  }

  @Test
  public void estimateDoesNotGrowPastTheExecutionGasLimitToFundANestedCallFromTheReservoir()
      throws Exception {
    final long gas = estimate(ApiConfiguration.DEFAULT_GAS_CAP, call(STORE_CATCHER));

    assertThat(gas).isLessThan(TX_MAX_GAS_LIMIT);
  }

  private long estimate(final long gasCap, final String call) throws Exception {
    final Object result = success(ethEstimateGas(gasCap), "eth_estimateGas", atLatest(call));
    return Long.decode((String) result);
  }

  private EthEstimateGas ethEstimateGas(final long gasCap) {
    return new EthEstimateGas(blockchainQueries, transactionSimulator(gasCap), apiConfig(gasCap));
  }

  private EthCall ethCall(final long gasCap) {
    return new EthCall(blockchainQueries, transactionSimulator(gasCap), new NoOpMetricsSystem());
  }

  private TransactionSimulator transactionSimulator(final long gasCap) {
    return new TransactionSimulator(
        fixture.getBlockchain(),
        fixture.getStateArchive(),
        fixture.getProtocolSchedule(),
        MiningConfiguration.MINING_DISABLED,
        gasCap);
  }

  // An exact estimate, so that a search that ignores failed nested calls settles on the lowest gas
  // limit at which the top-level call succeeds.
  private static ApiConfiguration apiConfig(final long gasCap) {
    return ImmutableApiConfiguration.builder()
        .gasCap(gasCap)
        .estimateGasToleranceRatio(0.0)
        .build();
  }

  private static void putCode(final ObjectNode alloc, final String address, final String code) {
    alloc.putObject(address).put("code", code).put("balance", "0x0");
  }

  private static String call(final String to) {
    return "{\"from\":\"" + SENDER + "\",\"to\":\"" + to + "\"}";
  }

  private static String call(final String to, final long gas) {
    return "{\"from\":\""
        + SENDER
        + "\",\"to\":\""
        + to
        + "\",\"gas\":\"0x"
        + Long.toHexString(gas)
        + "\"}";
  }

  private static String atLatest(final String call) {
    return call + ",\"latest\"";
  }

  private static long word(final Object output) {
    return UInt256.fromBytes(Bytes.fromHexString((String) output)).toLong();
  }

  private static Object success(final JsonRpcMethod method, final String name, final String params)
      throws Exception {
    final JsonRpcResponse response = method.response(request(name, params));
    assertThat(response).isInstanceOf(JsonRpcSuccessResponse.class);
    return ((JsonRpcSuccessResponse) response).getResult();
  }

  private static JsonRpcRequestContext request(final String name, final String params)
      throws Exception {
    final String json =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + name + "\",\"params\":[" + params + "]}";
    return new JsonRpcRequestContext(new ObjectMapper().readValue(json, JsonRpcRequest.class));
  }
}
