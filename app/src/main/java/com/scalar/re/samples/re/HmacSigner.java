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
package com.scalar.re.samples.re;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Computes the ScalarRE HMAC signature for data-plane requests. For POST
 * endpoints (notify / pull / poll) the signed payload is the raw request body;
 * the signature is a hex-encoded HMAC-SHA256 sent in {@code X-ScalarRE-Signature},
 * with the epoch-millis timestamp in {@code X-ScalarRE-Timestamp}.
 */
public final class HmacSigner {

  private HmacSigner() {}

  public static String hexHmacSha256(String key, String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(sig.length * 2);
      for (byte b : sig) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to compute HMAC signature", e);
    }
  }
}
