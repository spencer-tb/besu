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

import static org.hyperledger.besu.evmtool.BuildTestSubCommand.COMMAND_NAME;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExitCodeGenerator;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * CLI subcommand that runs Ethereum Engine API test fixtures (blockchain_test_engine format)
 * through Besu's own block builder.
 *
 * <p>Each payload a fixture expects to be valid is first built through {@code
 * testing_buildBlockV1}, on the payload's parent and from its attributes, transactions and extra
 * data, and compared with the fixture's, field by field. The fixture's payload is then imported as
 * {@code engine-test} imports it. Payloads the fixture expects to be invalid are skipped.
 */
@Command(
    name = COMMAND_NAME,
    description = "Build and execute the payloads of an Ethereum Engine Test.",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider.class)
public class BuildTestSubCommand implements Runnable, IExitCodeGenerator {

  /** The name users type to invoke this subcommand, and the value of the picocli command name. */
  public static final String COMMAND_NAME = "build-test";

  @Option(
      names = {"--test-name"},
      description =
          "Limit execution to tests whose name contains the given substring, or matches the given"
              + " pattern (a regex, with * and ? as wildcards).")
  private @Nullable String testName = null;

  @Option(
      names = {"--test-name-regex"},
      description =
          "Limit execution to tests whose id matches the given regex, taken verbatim, as a hive"
              + " --sim.limit value.")
  private @Nullable String testNameRegex = null;

  @Option(
      names = {"--workers"},
      description =
          "Number of parallel workers for processing fixture files. Defaults to the number of"
              + " available cores (${DEFAULT-VALUE} here); lower it to run with less parallelism.")
  private int workers = Runtime.getRuntime().availableProcessors();

  @Option(
      names = {"--json-array"},
      description =
          "Output results as a JSON array: name, pass, fork, lastBlockHash, lastPayloadStatus, error,"
              + " rejections, built, mismatches.")
  private boolean jsonArray = false;

  @Option(
      names = {"--verbose"},
      description =
          "Print per-test progress output. By default only failing tests and the final summary are"
              + " printed.")
  private boolean verbose = false;

  @ParentCommand private final EvmToolCommand parentCommand;

  @Parameters private final List<Path> buildTestFiles = new ArrayList<>();

  private int exitCode = 0;

  /** Required by PicoCLI, which instantiates subcommands reflectively with no arguments. */
  @SuppressWarnings({"unused", "NullAway"}) // Picocli injects the parent after construction.
  public BuildTestSubCommand() {
    this(null);
  }

  BuildTestSubCommand(final EvmToolCommand parentCommand) {
    this.parentCommand = parentCommand;
  }

  @Override
  public void run() {
    final EngineTestSubCommand runner =
        EngineTestSubCommand.forBuildTest(
            parentCommand, testName, testNameRegex, workers, jsonArray, verbose, buildTestFiles);
    runner.run();
    exitCode = runner.getExitCode();
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }
}
