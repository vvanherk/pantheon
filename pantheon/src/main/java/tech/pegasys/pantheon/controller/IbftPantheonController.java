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
package tech.pegasys.pantheon.controller;

import static org.apache.logging.log4j.LogManager.getLogger;

import tech.pegasys.pantheon.config.GenesisConfigFile;
import tech.pegasys.pantheon.config.IbftConfigOptions;
import tech.pegasys.pantheon.consensus.common.BlockInterface;
import tech.pegasys.pantheon.consensus.common.EpochManager;
import tech.pegasys.pantheon.consensus.common.VoteProposer;
import tech.pegasys.pantheon.consensus.common.VoteTallyCache;
import tech.pegasys.pantheon.consensus.common.VoteTallyUpdater;
import tech.pegasys.pantheon.consensus.ibft.BlockTimer;
import tech.pegasys.pantheon.consensus.ibft.EthSynchronizerUpdater;
import tech.pegasys.pantheon.consensus.ibft.EventMultiplexer;
import tech.pegasys.pantheon.consensus.ibft.IbftBlockInterface;
import tech.pegasys.pantheon.consensus.ibft.IbftContext;
import tech.pegasys.pantheon.consensus.ibft.IbftEventQueue;
import tech.pegasys.pantheon.consensus.ibft.IbftGossip;
import tech.pegasys.pantheon.consensus.ibft.IbftProcessor;
import tech.pegasys.pantheon.consensus.ibft.IbftProtocolSchedule;
import tech.pegasys.pantheon.consensus.ibft.RoundTimer;
import tech.pegasys.pantheon.consensus.ibft.UniqueMessageMulticaster;
import tech.pegasys.pantheon.consensus.ibft.blockcreation.IbftBlockCreatorFactory;
import tech.pegasys.pantheon.consensus.ibft.blockcreation.IbftMiningCoordinator;
import tech.pegasys.pantheon.consensus.ibft.blockcreation.ProposerSelector;
import tech.pegasys.pantheon.consensus.ibft.jsonrpc.IbftJsonRpcMethodsFactory;
import tech.pegasys.pantheon.consensus.ibft.network.ValidatorPeers;
import tech.pegasys.pantheon.consensus.ibft.payload.MessageFactory;
import tech.pegasys.pantheon.consensus.ibft.protocol.IbftProtocolManager;
import tech.pegasys.pantheon.consensus.ibft.protocol.IbftSubProtocol;
import tech.pegasys.pantheon.consensus.ibft.statemachine.IbftBlockHeightManagerFactory;
import tech.pegasys.pantheon.consensus.ibft.statemachine.IbftController;
import tech.pegasys.pantheon.consensus.ibft.statemachine.IbftFinalState;
import tech.pegasys.pantheon.consensus.ibft.statemachine.IbftRoundFactory;
import tech.pegasys.pantheon.consensus.ibft.validation.MessageValidatorFactory;
import tech.pegasys.pantheon.crypto.SECP256K1.KeyPair;
import tech.pegasys.pantheon.ethereum.ProtocolContext;
import tech.pegasys.pantheon.ethereum.blockcreation.MiningCoordinator;
import tech.pegasys.pantheon.ethereum.chain.GenesisState;
import tech.pegasys.pantheon.ethereum.chain.MinedBlockObserver;
import tech.pegasys.pantheon.ethereum.chain.MutableBlockchain;
import tech.pegasys.pantheon.ethereum.core.MiningParameters;
import tech.pegasys.pantheon.ethereum.core.PrivacyParameters;
import tech.pegasys.pantheon.ethereum.core.Synchronizer;
import tech.pegasys.pantheon.ethereum.core.TransactionPool;
import tech.pegasys.pantheon.ethereum.core.Util;
import tech.pegasys.pantheon.ethereum.eth.EthProtocol;
import tech.pegasys.pantheon.ethereum.eth.manager.EthContext;
import tech.pegasys.pantheon.ethereum.eth.manager.EthProtocolManager;
import tech.pegasys.pantheon.ethereum.eth.sync.DefaultSynchronizer;
import tech.pegasys.pantheon.ethereum.eth.sync.SyncMode;
import tech.pegasys.pantheon.ethereum.eth.sync.SynchronizerConfiguration;
import tech.pegasys.pantheon.ethereum.eth.sync.state.SyncState;
import tech.pegasys.pantheon.ethereum.eth.transactions.TransactionPoolFactory;
import tech.pegasys.pantheon.ethereum.jsonrpc.RpcApi;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.methods.JsonRpcMethod;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.ethereum.p2p.api.ProtocolManager;
import tech.pegasys.pantheon.ethereum.p2p.config.SubProtocolConfiguration;
import tech.pegasys.pantheon.ethereum.p2p.wire.SubProtocol;
import tech.pegasys.pantheon.ethereum.storage.StorageProvider;
import tech.pegasys.pantheon.metrics.MetricsSystem;
import tech.pegasys.pantheon.util.Subscribers;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;

public class IbftPantheonController implements PantheonController<IbftContext> {

  private static final Logger LOG = getLogger();
  private final ProtocolSchedule<IbftContext> protocolSchedule;
  private final ProtocolContext<IbftContext> context;
  private final Synchronizer synchronizer;
  private final SubProtocol ethSubProtocol;
  private final ProtocolManager ethProtocolManager;
  private final IbftProtocolManager ibftProtocolManager;
  private final KeyPair keyPair;
  private final TransactionPool transactionPool;
  private MiningCoordinator ibftMiningCoordinator;
  private final Runnable closer;

  IbftPantheonController(
      final ProtocolSchedule<IbftContext> protocolSchedule,
      final ProtocolContext<IbftContext> context,
      final SubProtocol ethSubProtocol,
      final ProtocolManager ethProtocolManager,
      final IbftProtocolManager ibftProtocolManager,
      final Synchronizer synchronizer,
      final KeyPair keyPair,
      final TransactionPool transactionPool,
      final MiningCoordinator ibftMiningCoordinator,
      final Runnable closer) {
    this.protocolSchedule = protocolSchedule;
    this.context = context;
    this.ethSubProtocol = ethSubProtocol;
    this.ethProtocolManager = ethProtocolManager;
    this.ibftProtocolManager = ibftProtocolManager;
    this.synchronizer = synchronizer;
    this.keyPair = keyPair;
    this.transactionPool = transactionPool;
    this.ibftMiningCoordinator = ibftMiningCoordinator;
    this.closer = closer;
  }

  public static PantheonController<IbftContext> init(
      final StorageProvider storageProvider,
      final GenesisConfigFile genesisConfig,
      final SynchronizerConfiguration syncConfig,
      final MiningParameters miningParams,
      final int networkId,
      final KeyPair nodeKeys,
      final Path dataDirectory,
      final MetricsSystem metricsSystem) {
    final ProtocolSchedule<IbftContext> protocolSchedule =
        IbftProtocolSchedule.create(genesisConfig.getConfigOptions());
    final GenesisState genesisState = GenesisState.fromConfig(genesisConfig, protocolSchedule);

    final BlockInterface blockInterface = new IbftBlockInterface();

    final IbftConfigOptions ibftConfig = genesisConfig.getConfigOptions().getIbft2ConfigOptions();

    final ProtocolContext<IbftContext> protocolContext =
        ProtocolContext.init(
            storageProvider,
            genesisState,
            protocolSchedule,
            metricsSystem,
            (blockchain, worldStateArchive) -> {
              final EpochManager epochManager = new EpochManager(ibftConfig.getEpochLength());
              return new IbftContext(
                  new VoteTallyCache(
                      blockchain,
                      new VoteTallyUpdater(epochManager, new IbftBlockInterface()),
                      epochManager,
                      new IbftBlockInterface()),
                  new VoteProposer());
            });
    final MutableBlockchain blockchain = protocolContext.getBlockchain();

    final boolean fastSyncEnabled = syncConfig.syncMode().equals(SyncMode.FAST);
    final EthProtocolManager ethProtocolManager =
        new EthProtocolManager(
            protocolContext.getBlockchain(),
            protocolContext.getWorldStateArchive(),
            networkId,
            fastSyncEnabled,
            syncConfig.downloaderParallelism(),
            syncConfig.transactionsParallelism(),
            syncConfig.computationParallelism(),
            metricsSystem);
    final SubProtocol ethSubProtocol = EthProtocol.get();

    final EthContext ethContext = ethProtocolManager.ethContext();

    final SyncState syncState =
        new SyncState(protocolContext.getBlockchain(), ethContext.getEthPeers());
    final Synchronizer synchronizer =
        new DefaultSynchronizer<>(
            syncConfig,
            protocolSchedule,
            protocolContext,
            protocolContext.getWorldStateArchive().getStorage(),
            ethProtocolManager.ethContext(),
            syncState,
            dataDirectory,
            metricsSystem);

    final TransactionPool transactionPool =
        TransactionPoolFactory.createTransactionPool(protocolSchedule, protocolContext, ethContext);

    final IbftEventQueue ibftEventQueue = new IbftEventQueue(ibftConfig.getMessageQueueLimit());

    final IbftBlockCreatorFactory blockCreatorFactory =
        new IbftBlockCreatorFactory(
            (gasLimit) -> gasLimit,
            transactionPool.getPendingTransactions(),
            protocolContext,
            protocolSchedule,
            miningParams,
            Util.publicKeyToAddress(nodeKeys.getPublicKey()));

    final ProposerSelector proposerSelector =
        new ProposerSelector(blockchain, blockInterface, true);

    // NOTE: peers should not be used for accessing the network as it does not enforce the
    // "only send once" filter applied by the UniqueMessageMulticaster.
    final VoteTallyCache voteTallyCache = protocolContext.getConsensusState().getVoteTallyCache();
    final ValidatorPeers peers = new ValidatorPeers(voteTallyCache);

    final UniqueMessageMulticaster uniqueMessageMulticaster =
        new UniqueMessageMulticaster(peers, ibftConfig.getGossipedHistoryLimit());

    final IbftGossip gossiper = new IbftGossip(uniqueMessageMulticaster);

    final IbftFinalState finalState =
        new IbftFinalState(
            voteTallyCache,
            nodeKeys,
            Util.publicKeyToAddress(nodeKeys.getPublicKey()),
            proposerSelector,
            uniqueMessageMulticaster,
            new RoundTimer(
                ibftEventQueue,
                ibftConfig.getRequestTimeoutSeconds(),
                Executors.newScheduledThreadPool(1)),
            new BlockTimer(
                ibftEventQueue,
                ibftConfig.getBlockPeriodSeconds(),
                Executors.newScheduledThreadPool(1),
                Clock.systemUTC()),
            blockCreatorFactory,
            new MessageFactory(nodeKeys),
            Clock.systemUTC());

    final MessageValidatorFactory messageValidatorFactory =
        new MessageValidatorFactory(proposerSelector, protocolSchedule, protocolContext);

    final Subscribers<MinedBlockObserver> minedBlockObservers = new Subscribers<>();
    minedBlockObservers.subscribe(ethProtocolManager);

    final IbftController ibftController =
        new IbftController(
            blockchain,
            finalState,
            new IbftBlockHeightManagerFactory(
                finalState,
                new IbftRoundFactory(
                    finalState,
                    protocolContext,
                    protocolSchedule,
                    minedBlockObservers,
                    messageValidatorFactory),
                messageValidatorFactory),
            gossiper,
            ibftConfig.getDuplicateMessageLimit(),
            new EthSynchronizerUpdater(ethContext.getEthPeers()));
    ibftController.start();

    final EventMultiplexer eventMultiplexer = new EventMultiplexer(ibftController);
    final IbftProcessor ibftProcessor = new IbftProcessor(ibftEventQueue, eventMultiplexer);
    final ExecutorService processorExecutor = Executors.newSingleThreadExecutor();
    processorExecutor.submit(ibftProcessor);

    final MiningCoordinator ibftMiningCoordinator =
        new IbftMiningCoordinator(ibftProcessor, blockCreatorFactory, blockchain, ibftEventQueue);
    ibftMiningCoordinator.enable();

    final Runnable closer =
        () -> {
          ibftProcessor.stop();
          ibftMiningCoordinator.disable();
          processorExecutor.shutdownNow();
          try {
            processorExecutor.awaitTermination(5, TimeUnit.SECONDS);
          } catch (final InterruptedException e) {
            LOG.error("Failed to shutdown ibft processor executor");
          }
          try {
            storageProvider.close();
          } catch (final IOException e) {
            LOG.error("Failed to close storage provider", e);
          }
        };

    return new IbftPantheonController(
        protocolSchedule,
        protocolContext,
        ethSubProtocol,
        ethProtocolManager,
        new IbftProtocolManager(ibftEventQueue, peers),
        synchronizer,
        nodeKeys,
        transactionPool,
        ibftMiningCoordinator,
        closer);
  }

  @Override
  public ProtocolContext<IbftContext> getProtocolContext() {
    return context;
  }

  @Override
  public ProtocolSchedule<IbftContext> getProtocolSchedule() {
    return protocolSchedule;
  }

  @Override
  public Synchronizer getSynchronizer() {
    return synchronizer;
  }

  @Override
  public SubProtocolConfiguration subProtocolConfiguration() {
    return new SubProtocolConfiguration()
        .withSubProtocol(ethSubProtocol, ethProtocolManager)
        .withSubProtocol(IbftSubProtocol.get(), ibftProtocolManager);
  }

  @Override
  public KeyPair getLocalNodeKeyPair() {
    return keyPair;
  }

  @Override
  public TransactionPool getTransactionPool() {
    return transactionPool;
  }

  @Override
  public MiningCoordinator getMiningCoordinator() {
    return ibftMiningCoordinator;
  }

  @Override
  public PrivacyParameters getPrivacyParameters() {
    return PrivacyParameters.noPrivacy();
  }

  @Override
  public Map<String, JsonRpcMethod> getAdditionalJsonRpcMethods(
      final Collection<RpcApi> enabledRpcApis) {
    return new IbftJsonRpcMethodsFactory().methods(context, enabledRpcApis);
  }

  @Override
  public void close() {
    closer.run();
  }
}
