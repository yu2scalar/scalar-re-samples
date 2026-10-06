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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The scan APIs return a page ({@code records} + {@code next_cursor}); the demo shows its records. */
class ReRestClientRecordsTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void page_returnsItsRecordsArray() throws Exception {
    JsonNode page = mapper.readTree("{\"records\":[{\"eventId\":\"a\"}],\"next_cursor\":null}");

    JsonNode records = ReRestClient.records(page);

    assertThat(records.isArray()).isTrue();
    assertThat(records.get(0).get("eventId").asText()).isEqualTo("a");
  }

  @Test
  void nodeWithoutRecords_isReturnedAsIs() throws Exception {
    JsonNode array = mapper.readTree("[{\"eventId\":\"a\"}]");

    assertThat(ReRestClient.records(array)).isSameAs(array);
  }

  @Test
  void null_isReturnedAsNull() {
    assertThat(ReRestClient.records(null)).isNull();
  }
}
