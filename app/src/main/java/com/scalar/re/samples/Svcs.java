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

import java.util.List;

/**
 * Naming helpers for the two demo services. Svc1 sends to Svc2 and vice versa;
 * a service owns the event types named after it (e.g. svc1 -> Svc1Atomic).
 */
public final class Svcs {

  public static final List<String> SERVICES = List.of("svc1", "svc2");
  public static final List<String> DELIVERY_TYPES = List.of("atomic", "qpull");

  private Svcs() {}

  /** The other service (message destination). */
  public static String other(String svc) {
    return svc.equals("svc1") ? "svc2" : "svc1";
  }

  /** Event type this service produces for a delivery type, e.g. (svc1, atomic) -> Svc1Atomic. */
  public static String outboundEventType(String svc, String deliveryType) {
    return cap(svc) + cap(deliveryType);
  }

  /** Event type this service receives (produced by the other service), e.g. svc2 sees Svc1Atomic. */
  public static String inboundEventType(String svc, String deliveryType) {
    return cap(other(svc)) + cap(deliveryType);
  }

  private static String cap(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
