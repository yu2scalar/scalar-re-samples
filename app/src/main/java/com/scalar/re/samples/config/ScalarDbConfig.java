/*
 * Copyright 2026 Scalar Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.scalar.re.samples.config;

import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.service.TransactionFactory;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the ScalarDB transaction manager the producer uses to write re_outbox. */
@Configuration
public class ScalarDbConfig {

  @Bean(name = "scalarDbTransactionManager", destroyMethod = "close")
  public DistributedTransactionManager scalarDbTransactionManager(ReConfig reConfig)
      throws IOException {
    return TransactionFactory.create(reConfig.scalarDbProperties()).getTransactionManager();
  }
}
