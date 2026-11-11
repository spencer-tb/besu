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
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.api.ApiConfiguration;
import org.hyperledger.besu.ethereum.api.ImmutableApiConfiguration;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequest;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.BlockStateCallResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.CallProcessingResult;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.core.ExecutionContextTestFixture;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.transaction.TransactionSimulator;
import org.hyperledger.besu.ethereum.transaction.exceptions.BlockStateCallError;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
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
  // an RPC gas cap below the block gas limit
  private static final long LOW_GAS_CAP = 10_000_000L;
  // gas that a call needs, above LOW_GAS_CAP and below TX_MAX_GAS_LIMIT
  private static final long NEEDED_GAS = 12_000_000L;

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
  // Returns the gas left at entry if it is at least NEEDED_GAS, reverts otherwise:
  //   GAS PUSH4 NEEDED_GAS DUP2 LT PUSH1 0x11 JUMPI PUSH0 MSTORE PUSH1 0x20 PUSH0 RETURN
  //   JUMPDEST PUSH0 PUSH0 REVERT
  private static final String NEEDS_GAS = "0x000000000000000000000000000000000000c0de";
  private static final String NEEDS_GAS_CODE = "0x5a6300b71b0081106011575f5260205ff35b5f5ffd";
  // Returns the gas left at entry: GAS PUSH0 MSTORE PUSH1 0x20 PUSH0 RETURN
  private static final String GAS_AT_CALL = "0x000000000000000000000000000000000000c0df";
  private static final String GAS_AT_CALL_CODE = "0x5a5f5260205ff3";
  // gas above TX_MAX_GAS_LIMIT and below the block gas limit
  private static final long GAS_ABOVE_TX_MAX = 25_000_000L;
  // Returns the gas left at entry if it is at least 20,000,000, above TX_MAX_GAS_LIMIT, reverts
  // otherwise. As NEEDS_GAS, with PUSH4 20000000.
  private static final String NEEDS_EXECUTION_GAS = "0x000000000000000000000000000000000000c0e0";
  private static final String NEEDS_EXECUTION_GAS_CODE =
      "0x5a6301312d0081106011575f5260205ff35b5f5ffd";
  // Returns the code size of the address in its input:
  //   PUSH1 0 CALLDATALOAD EXTCODESIZE PUSH0 MSTORE PUSH1 0x20 PUSH0 RETURN
  private static final String CODE_SIZE = "0x000000000000000000000000000000000000c0e1";
  private static final String CODE_SIZE_CODE = "0x6000353b5f5260205ff3";
  // Init code that deploys two contracts of 24,576 bytes and ignores whether that succeeds. Each
  // deployment needs about 37.8M state gas, so the two only fit with a reservoir:
  //   PUSH6 (PUSH2 0x6000 PUSH1 0 RETURN) PUSH1 0 MSTORE
  //   PUSH1 6 PUSH1 26 PUSH1 0 CREATE POP PUSH1 6 PUSH1 26 PUSH1 0 CREATE POP STOP
  private static final String TWO_DEPLOYMENTS =
      "0x656160006000f36000526006601a6000f0506006601a6000f05000";
  private static final int DEPLOYED_CODE_SIZE = 0x6000;
  // a block gas limit that fits the two deployments
  private static final long LARGE_BLOCK_GAS_LIMIT = 100_000_000L;

  private final ObjectMapper mapper = new ObjectMapper();

  private ExecutionContextTestFixture fixture;
  private BlockchainQueries blockchainQueries;

  @BeforeEach
  public void setUp() throws Exception {
    setUpChain(BLOCK_GAS_LIMIT);
  }

  private void setUpChain(final long blockGasLimit) throws Exception {
    final ObjectNode genesis =
        (ObjectNode) mapper.readTree(CallGasLimitsTest.class.getResource(GENESIS_RESOURCE));
    ((ObjectNode) genesis.get("config")).put("amsterdamTime", 0);
    genesis.put("gasLimit", "0x" + Long.toHexString(blockGasLimit));
    final ObjectNode alloc = (ObjectNode) genesis.get("alloc");
    putCode(alloc, BURNER, BURNER_CODE);
    putCode(alloc, CATCHER, CATCHER_CODE);
    putCode(alloc, PROBER, PROBER_CODE);
    putCode(alloc, STORER, STORER_CODE);
    putCode(alloc, STORE_CATCHER, STORE_CATCHER_CODE);
    putCode(alloc, NEEDS_GAS, NEEDS_GAS_CODE);
    putCode(alloc, GAS_AT_CALL, GAS_AT_CALL_CODE);
    putCode(alloc, NEEDS_EXECUTION_GAS, NEEDS_EXECUTION_GAS_CODE);
    putCode(alloc, CODE_SIZE, CODE_SIZE_CODE);
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
    assertThat(fixture.getGenesis().getHeader().getGasLimit()).isEqualTo(blockGasLimit);
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

  @Test
  public void estimateAboveTheExecutionGasLimitLetsEveryNestedCreationSucceed() throws Exception {
    setUpChain(LARGE_BLOCK_GAS_LIMIT);
    final long gas =
        estimate(
            ApiConfiguration.DEFAULT_GAS_CAP,
            "{\"from\":\"" + SENDER + "\",\"input\":\"" + TWO_DEPLOYMENTS + "\"}");
    assertThat(gas).isBetween(TX_MAX_GAS_LIMIT, LARGE_BLOCK_GAS_LIMIT);

    // deploy at the estimate as in a block, then read the code size of both created contracts
    final Address deployer = Address.contractAddress(Address.fromHexString(SENDER), 0L);
    final String deployment =
        "{\"from\":\""
            + SENDER
            + "\",\"gas\":\"0x"
            + Long.toHexString(gas)
            + "\",\"maxFeePerGas\":\"0x0\",\"input\":\""
            + TWO_DEPLOYMENTS
            + "\"}";
    final Object result =
        success(
            ethSimulateV1(ApiConfiguration.DEFAULT_GAS_CAP),
            "eth_simulateV1",
            simulation(
                "{\"baseFeePerGas\":\"0x0\"}",
                deployment
                    + ","
                    + codeSizeCall(Address.contractAddress(deployer, 1L))
                    + ","
                    + codeSizeCall(Address.contractAddress(deployer, 2L)),
                true));
    @SuppressWarnings("unchecked")
    final List<CallProcessingResult> calls =
        ((List<BlockStateCallResult>) result).get(0).getTransactionProcessingResults();
    assertThat(calls).hasSize(3);
    assertThat(calls.get(0).getStatus()).isEqualTo("0x1");
    assertThat(word(calls.get(1).getReturnData())).isEqualTo(DEPLOYED_CODE_SIZE);
    assertThat(word(calls.get(2).getReturnData()))
        .as("second contract deployed at the estimate %d", gas)
        .isEqualTo(DEPLOYED_CODE_SIZE);
  }

  @Test
  public void callWithGasAboveTheCapGetsItUpToTheBlockGasLimit() throws Exception {
    final Object output =
        success(ethCall(LOW_GAS_CAP), "eth_call", atLatest(call(NEEDS_GAS, 20_000_000L)));

    assertThat(word(output)).isGreaterThanOrEqualTo(NEEDED_GAS);
  }

  @Test
  public void callWithGasAboveTheBlockGasLimitIsCappedAtTheBlockGasLimit() throws Exception {
    final Object output =
        success(ethCall(LOW_GAS_CAP), "eth_call", atLatest(call(GAS_AT_CALL, 1_000_000_000L)));

    assertThat(word(output)).isLessThanOrEqualTo(BLOCK_GAS_LIMIT);
  }

  @Test
  public void callWithoutGasKeepsTheCapAsDefault() throws Exception {
    final Object output = success(ethCall(LOW_GAS_CAP), "eth_call", atLatest(call(GAS_AT_CALL)));

    assertThat(word(output)).isLessThanOrEqualTo(LOW_GAS_CAP);
  }

  @Test
  public void estimateSearchReachesTheBlockGasLimitAboveTheCap() throws Exception {
    final long gas = estimate(LOW_GAS_CAP, call(NEEDS_GAS));

    assertThat(gas).isBetween(NEEDED_GAS, BLOCK_GAS_LIMIT);
  }

  @Test
  public void simulateCallWithGasAboveTheCapGetsItUpToTheBlockGasLimit() throws Exception {
    final CallProcessingResult result =
        simulatedCall(LOW_GAS_CAP, simulation("{}", call(NEEDS_GAS, 20_000_000L), false));

    assertThat(result.getStatus()).isEqualTo("0x1");
    assertThat(word(result.getReturnData())).isGreaterThanOrEqualTo(NEEDED_GAS);
  }

  @Test
  public void simulateCallWithoutGasKeepsTheCapAsDefault() throws Exception {
    final CallProcessingResult result =
        simulatedCall(LOW_GAS_CAP, simulation("{}", call(GAS_AT_CALL), false));

    assertThat(result.getStatus()).isEqualTo("0x1");
    assertThat(word(result.getReturnData())).isLessThanOrEqualTo(LOW_GAS_CAP);
  }

  @Test
  public void simulateBlockOverrideOfTheGasLimitDoesNotRaiseTheCap() throws Exception {
    final JsonRpcError error =
        error(
            ethSimulateV1(LOW_GAS_CAP),
            "eth_simulateV1",
            simulation("{\"gasLimit\":\"0x3b9aca00\"}", call(GAS_AT_CALL, 40_000_000L), false));

    assertThat(error.getCode()).isEqualTo(BlockStateCallError.BLOCK_GAS_LIMIT_EXCEEDED.getCode());
  }

  @Test
  public void callCanSpendItsWholeGasLimitOnExecution() throws Exception {
    final Object output =
        success(
            ethCall(ApiConfiguration.DEFAULT_GAS_CAP),
            "eth_call",
            atLatest(call(GAS_AT_CALL, GAS_ABOVE_TX_MAX)));

    assertThat(word(output)).isGreaterThan(TX_MAX_GAS_LIMIT);
  }

  @Test
  public void estimateCapsExecutionGasAsInABlock() throws Exception {
    final EthEstimateGas method = ethEstimateGas(ApiConfiguration.DEFAULT_GAS_CAP);

    // the call needs more execution gas than a transaction can have, so no gas limit suffices,
    // with or without strict mode
    error(method, "eth_estimateGas", atLatest(call(NEEDS_EXECUTION_GAS)));
    error(
        method,
        "eth_estimateGas",
        atLatest(
            "{\"from\":\""
                + SENDER
                + "\",\"to\":\""
                + NEEDS_EXECUTION_GAS
                + "\",\"strict\":false}"));
  }

  @Test
  public void simulateWithValidationCapsExecutionGasAsInABlock() throws Exception {
    final CallProcessingResult result =
        simulatedCall(
            ApiConfiguration.DEFAULT_GAS_CAP,
            simulation(
                "{\"baseFeePerGas\":\"0x0\"}", unpricedCall(GAS_AT_CALL, GAS_ABOVE_TX_MAX), true));

    assertThat(result.getStatus()).isEqualTo("0x1");
    assertThat(word(result.getReturnData())).isLessThan(TX_MAX_GAS_LIMIT);
  }

  @Test
  public void simulateWithoutValidationCanSpendTheWholeGasLimitOnExecution() throws Exception {
    final CallProcessingResult result =
        simulatedCall(
            ApiConfiguration.DEFAULT_GAS_CAP,
            simulation("{}", call(GAS_AT_CALL, GAS_ABOVE_TX_MAX), false));

    assertThat(result.getStatus()).isEqualTo("0x1");
    assertThat(word(result.getReturnData())).isGreaterThan(TX_MAX_GAS_LIMIT);
  }

  private CallProcessingResult simulatedCall(final long gasCap, final String simulation)
      throws Exception {
    final Object result = success(ethSimulateV1(gasCap), "eth_simulateV1", simulation);
    @SuppressWarnings("unchecked")
    final List<BlockStateCallResult> blocks = (List<BlockStateCallResult>) result;
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).getTransactionProcessingResults()).hasSize(1);
    return blocks.get(0).getTransactionProcessingResults().get(0);
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

  private EthSimulateV1 ethSimulateV1(final long gasCap) {
    return new EthSimulateV1(
        null,
        blockchainQueries,
        fixture.getProtocolSchedule(),
        transactionSimulator(gasCap),
        MiningConfiguration.MINING_DISABLED,
        apiConfig(gasCap));
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

  // a call to CODE_SIZE for the given address, valid in a block with a zero base fee
  private static String codeSizeCall(final Address address) {
    return "{\"from\":\""
        + SENDER
        + "\",\"to\":\""
        + CODE_SIZE
        + "\",\"gas\":\"0x186a0\",\"maxFeePerGas\":\"0x0\",\"input\":\""
        + Bytes32.leftPad(address.getBytes()).toHexString()
        + "\"}";
  }

  // a call with a zero fee cap, valid in a block with a zero base fee
  private static String unpricedCall(final String to, final long gas) {
    return "{\"from\":\""
        + SENDER
        + "\",\"to\":\""
        + to
        + "\",\"gas\":\"0x"
        + Long.toHexString(gas)
        + "\",\"maxFeePerGas\":\"0x0\"}";
  }

  private static String atLatest(final String call) {
    return call + ",\"latest\"";
  }

  private static String simulation(
      final String blockOverrides, final String call, final boolean validation) {
    return atLatest(
        "{\"blockStateCalls\":[{\"blockOverrides\":"
            + blockOverrides
            + ",\"calls\":["
            + call
            + "]}],\"validation\":"
            + validation
            + "}");
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

  private static JsonRpcError error(
      final JsonRpcMethod method, final String name, final String params) throws Exception {
    final JsonRpcResponse response = method.response(request(name, params));
    assertThat(response).isInstanceOf(JsonRpcErrorResponse.class);
    return ((JsonRpcErrorResponse) response).getError();
  }

  private static JsonRpcRequestContext request(final String name, final String params)
      throws Exception {
    final String json =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + name + "\",\"params\":[" + params + "]}";
    return new JsonRpcRequestContext(new ObjectMapper().readValue(json, JsonRpcRequest.class));
  }
}
