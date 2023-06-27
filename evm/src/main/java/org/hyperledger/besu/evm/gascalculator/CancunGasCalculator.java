/*
 * Copyright Hyperledger Besu contributors.
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
package org.hyperledger.besu.evm.gascalculator;

import static org.hyperledger.besu.datatypes.Address.KZG_POINT_EVAL;

/**
 * Gas Calculator for Cancun
 *
 * <UL>
 *   <LI>Gas costs for TSTORE/TLOAD
 *   <LI>Data gas for EIP-4844
 * </UL>
 */
public class CancunGasCalculator extends ShanghaiGasCalculator {

  /** Instantiates a new Cancun Gas Calculator. */
  public CancunGasCalculator() {
    this(KZG_POINT_EVAL.toArrayUnsafe()[19]);
  }

  /**
   * Instantiates a new Cancun Gas Calculator
   *
   * @param maxPrecompile the max precompile
   */
  private CancunGasCalculator(final int maxPrecompile) {
    super(maxPrecompile);
  }

  private static final long TLOAD_GAS = WARM_STORAGE_READ_COST;
  private static final long TSTORE_GAS = WARM_STORAGE_READ_COST;

  private static final long DATA_GAS_PER_BLOB = 1 << 17;
  private static final long TARGET_DATA_GAS_PER_BLOCK = 393216L;

  // EIP-1153
  @Override
  public long getTransientLoadOperationGasCost() {
    return TLOAD_GAS;
  }

  @Override
  public long getTransientStoreOperationGasCost() {
    return TSTORE_GAS;
  }

  @Override
  public long dataGasUsed(final int blobCount) {
    return DATA_GAS_PER_BLOB * blobCount;
  }

  @Override
  public long computeExcessDataGas(final long parentExcessDataGas, final long parentDataGasUsed) {
    if (parentExcessDataGas + parentDataGasUsed < TARGET_DATA_GAS_PER_BLOCK) {
      return 0L;
    }
    return parentDataGasUsed + parentDataGasUsed - TARGET_DATA_GAS_PER_BLOCK;
  }
}
