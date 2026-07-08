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

import com.scalar.re.samples.SamplesProperties;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses the shared ScalarRE config file and exposes what the sample app needs:
 * the API key, per-namespace HMAC keys and event types, and the JDBC / ScalarDB
 * connection details for the service databases.
 *
 * <p>This deliberately reuses the exact same config file the RE server loads, so
 * the demo has a single source of truth for database addresses and credentials.
 */
@Component
public class ReConfig {

  /** A storage backend (one physical database). */
  public record Storage(String jdbcUrl, String username, String password) {}

  /** A namespace: which storage it lives in, its HMAC key, and its event types. */
  public record Namespace(String storage, String hmacKey, Map<String, String> eventTypeDeliveryTypes) {}

  private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z0-9_]+)(?::([^}]*))?}");

  private final String apiKey;
  private final String defaultStorage;
  private final Map<String, Storage> storages = new LinkedHashMap<>();
  private final Map<String, Namespace> namespaces = new LinkedHashMap<>();

  @SuppressWarnings("unchecked")
  public ReConfig(SamplesProperties props) throws Exception {
    Map<String, Object> root;
    try (InputStream in = new FileInputStream(props.getReConfigPath())) {
      root = new Yaml().load(in);
    }

    Map<String, Object> global = asMap(root.get("global"));
    Map<String, Object> auth = asMap(global.get("auth"));
    this.apiKey = str(auth.get("api-key"));

    Map<String, Object> scalardb = asMap(root.get("scalardb"));
    String ds = str(scalardb.get("default-storage"));

    Map<String, Object> rawStorages = asMap(root.get("storages"));
    for (Map.Entry<String, Object> e : rawStorages.entrySet()) {
      Map<String, Object> st = asMap(e.getValue());
      String host = str(st.get("host"));
      String port = str(st.get("port"));
      String database = str(st.get("database"));
      String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;
      storages.put(e.getKey(), new Storage(jdbcUrl, str(st.get("username")), str(st.get("password"))));
    }
    this.defaultStorage = ds.isEmpty() ? storages.keySet().iterator().next() : ds;

    Map<String, Object> rawNamespaces = asMap(root.get("namespaces"));
    for (Map.Entry<String, Object> e : rawNamespaces.entrySet()) {
      Map<String, Object> ns = asMap(e.getValue());
      String storage = str(ns.get("storage"));
      Map<String, Object> hmac = asMap(ns.get("hmac"));
      String hmacKey = str(hmac.get("key"));
      Map<String, String> ets = new LinkedHashMap<>();
      Map<String, Object> rawEts = asMap(ns.get("event-types"));
      for (Map.Entry<String, Object> et : rawEts.entrySet()) {
        Map<String, Object> etv = asMap(et.getValue());
        ets.put(et.getKey(), str(etv.get("delivery-type")));
      }
      namespaces.put(e.getKey(), new Namespace(storage, hmacKey, ets));
    }
  }

  public String apiKey() {
    return apiKey;
  }

  public Map<String, Namespace> namespaces() {
    return namespaces;
  }

  public Namespace namespace(String name) {
    return namespaces.get(name);
  }

  public Storage storage(String name) {
    return storages.get(name);
  }

  /** JDBC coordinates of the database that backs the given namespace. */
  public Storage storageForNamespace(String namespace) {
    return storages.get(namespaces.get(namespace).storage());
  }

  /**
   * Builds the multi-storage ScalarDB properties, mirroring the RE server's own
   * wiring (coordinator lives in the default storage). The producer uses this to
   * write re_outbox rows transactionally so the RE server picks them up.
   */
  public Properties scalarDbProperties() {
    Properties p = new Properties();
    List<String> names = new ArrayList<>(new TreeMap<>(storagesAsSortedMap()).keySet());

    p.setProperty("scalar.db.storage", "multi-storage");
    p.setProperty("scalar.db.transaction_manager", "consensus-commit");
    p.setProperty("scalar.db.consensus_commit.isolation_level", "READ_COMMITTED");
    p.setProperty("scalar.db.multi_storage.storages", String.join(",", names));

    for (String name : names) {
      Storage st = storages.get(name);
      String prefix = "scalar.db.multi_storage.storages." + name;
      p.setProperty(prefix + ".storage", "jdbc");
      p.setProperty(prefix + ".contact_points", st.jdbcUrl());
      p.setProperty(prefix + ".username", st.username());
      p.setProperty(prefix + ".password", st.password());
      p.setProperty(prefix + ".cross_partition_scan.enabled", "true");
      p.setProperty(prefix + ".cross_partition_scan.filtering.enabled", "true");
      p.setProperty(prefix + ".cross_partition_scan.ordering.enabled", "true");
    }

    List<String> mappings = new ArrayList<>();
    mappings.add("coordinator:" + defaultStorage);
    for (Map.Entry<String, Namespace> e : namespaces.entrySet()) {
      mappings.add(e.getKey() + ":" + e.getValue().storage());
    }
    p.setProperty("scalar.db.multi_storage.namespace_mapping", String.join(",", mappings));
    p.setProperty("scalar.db.multi_storage.default_storage", defaultStorage);
    return p;
  }

  private Map<String, Storage> storagesAsSortedMap() {
    return new TreeMap<>(storages);
  }

  // ---- YAML helpers -------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return o instanceof Map ? (Map<String, Object>) o : Map.of();
  }

  /** String value with ${ENV:default} resolution. */
  private static String str(Object o) {
    if (o == null) {
      return "";
    }
    String s = String.valueOf(o);
    Matcher m = ENV.matcher(s);
    if (!m.matches()) {
      return s;
    }
    String env = System.getenv(m.group(1));
    if (env != null && !env.isEmpty()) {
      return env;
    }
    return m.group(2) == null ? "" : m.group(2);
  }
}
