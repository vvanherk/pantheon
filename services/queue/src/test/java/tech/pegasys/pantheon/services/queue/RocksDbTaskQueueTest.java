/*
 * Copyright 2019 ConsenSys AG.
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
package tech.pegasys.pantheon.services.queue;

import tech.pegasys.pantheon.metrics.noop.NoOpMetricsSystem;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.io.IOException;
import java.util.function.Function;

import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public class RocksDbTaskQueueTest extends AbstractTaskQueueTest<RocksDbTaskQueue<BytesValue>> {

  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Override
  protected RocksDbTaskQueue<BytesValue> createQueue() throws IOException {
    return RocksDbTaskQueue.create(
        folder.newFolder().toPath(),
        Function.identity(),
        Function.identity(),
        new NoOpMetricsSystem());
  }
}
