/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.jsonrpc.internal.methods;

import tech.pegasys.pantheon.ethereum.core.Address;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.JsonRpcRequest;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.BlockParameter;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.JsonRpcParameter;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.queries.BlockchainQueries;
import tech.pegasys.pantheon.util.bytes.BytesValue;

public class EthGetCode extends AbstractBlockParameterMethod {

  public EthGetCode(final BlockchainQueries blockchainQueries, final JsonRpcParameter parameters) {
    super(blockchainQueries, parameters);
  }

  @Override
  public String getName() {
    return "eth_getCode";
  }

  @Override
  protected BlockParameter blockParameter(final JsonRpcRequest request) {
    return parameters().required(request.getParams(), 1, BlockParameter.class);
  }

  @Override
  protected String resultByBlockNumber(final JsonRpcRequest request, final long blockNumber) {
    final Address address = parameters().required(request.getParams(), 0, Address.class);
    return blockchainQueries().getCode(address, blockNumber).map(BytesValue::toString).orElse(null);
  }
}
