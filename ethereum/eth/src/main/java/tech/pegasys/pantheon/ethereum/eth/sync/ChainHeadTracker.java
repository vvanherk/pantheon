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
package tech.pegasys.pantheon.ethereum.eth.sync;

import static org.apache.logging.log4j.LogManager.getLogger;

import tech.pegasys.pantheon.ethereum.chain.Blockchain;
import tech.pegasys.pantheon.ethereum.core.BlockHeader;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.eth.manager.EthContext;
import tech.pegasys.pantheon.ethereum.eth.manager.EthPeer;
import tech.pegasys.pantheon.ethereum.eth.manager.EthPeers.ConnectCallback;
import tech.pegasys.pantheon.ethereum.eth.manager.task.GetHeadersFromPeerByHashTask;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.ethereum.p2p.wire.messages.DisconnectMessage.DisconnectReason;
import tech.pegasys.pantheon.metrics.LabelledMetric;
import tech.pegasys.pantheon.metrics.OperationTimer;

import org.apache.logging.log4j.Logger;

public class ChainHeadTracker implements ConnectCallback {

  private static final Logger LOG = getLogger();

  private final EthContext ethContext;
  private final ProtocolSchedule<?> protocolSchedule;
  private final TrailingPeerLimiter trailingPeerLimiter;
  private final LabelledMetric<OperationTimer> ethTasksTimer;

  public ChainHeadTracker(
      final EthContext ethContext,
      final ProtocolSchedule<?> protocolSchedule,
      final TrailingPeerLimiter trailingPeerLimiter,
      final LabelledMetric<OperationTimer> ethTasksTimer) {
    this.ethContext = ethContext;
    this.protocolSchedule = protocolSchedule;
    this.trailingPeerLimiter = trailingPeerLimiter;
    this.ethTasksTimer = ethTasksTimer;
  }

  public static void trackChainHeadForPeers(
      final EthContext ethContext,
      final ProtocolSchedule<?> protocolSchedule,
      final Blockchain blockchain,
      final SynchronizerConfiguration syncConfiguration,
      final LabelledMetric<OperationTimer> ethTasksTimer) {
    final TrailingPeerLimiter trailingPeerLimiter =
        new TrailingPeerLimiter(
            ethContext.getEthPeers(),
            blockchain,
            syncConfiguration.trailingPeerBlocksBehindThreshold(),
            syncConfiguration.maxTrailingPeers());
    final ChainHeadTracker tracker =
        new ChainHeadTracker(ethContext, protocolSchedule, trailingPeerLimiter, ethTasksTimer);
    ethContext.getEthPeers().subscribeConnect(tracker);
    blockchain.observeBlockAdded(trailingPeerLimiter);
  }

  @Override
  public void onPeerConnected(final EthPeer peer) {
    LOG.debug("Requesting chain head info for {}", peer);
    GetHeadersFromPeerByHashTask.forSingleHash(
            protocolSchedule,
            ethContext,
            Hash.wrap(peer.chainState().getBestBlock().getHash()),
            ethTasksTimer)
        .assignPeer(peer)
        .run()
        .whenComplete(
            (peerResult, error) -> {
              if (peerResult != null && !peerResult.getResult().isEmpty()) {
                final BlockHeader chainHeadHeader = peerResult.getResult().get(0);
                peer.chainState().update(chainHeadHeader);
                trailingPeerLimiter.enforceTrailingPeerLimit();
              } else {
                LOG.debug("Failed to retrieve chain head information for " + peer, error);
                peer.disconnect(DisconnectReason.USELESS_PEER);
              }
            });
  }
}
