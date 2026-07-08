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
package com.scalar.re.samples;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Sample-app settings (see application.yml, prefix {@code samples}). */
@ConfigurationProperties(prefix = "samples")
public class SamplesProperties {
  /** Path to the shared ScalarRE config file (reused to discover svc DB info). */
  private String reConfigPath = "../config/scalar-re-config.yml";
  /** Base URL of the running RE server. */
  private String reBaseUrl = "http://localhost:8080";
  /** API key for the RE management/scan endpoints. */
  private String reApiKey = "demo-api-key-change-me";

  public String getReConfigPath() {
    return reConfigPath;
  }

  public void setReConfigPath(String reConfigPath) {
    this.reConfigPath = reConfigPath;
  }

  public String getReBaseUrl() {
    return reBaseUrl;
  }

  public void setReBaseUrl(String reBaseUrl) {
    this.reBaseUrl = reBaseUrl;
  }

  public String getReApiKey() {
    return reApiKey;
  }

  public void setReApiKey(String reApiKey) {
    this.reApiKey = reApiKey;
  }
}
