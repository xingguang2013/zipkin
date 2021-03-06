/**
 * Copyright 2015-2017 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.storage.elasticsearch.http;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import zipkin.Annotation;
import zipkin.BinaryAnnotation;
import zipkin.Codec;
import zipkin.Span;
import zipkin.TestObjects;
import zipkin.internal.CallbackCaptor;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static zipkin.Constants.CLIENT_SEND;
import static zipkin.Constants.SERVER_RECV;
import static zipkin.TestObjects.TODAY;
import static zipkin.internal.ApplyTimestampAndDuration.guessTimestamp;
import static zipkin.internal.Util.UTF_8;
import static zipkin.storage.elasticsearch.http.ElasticsearchHttpSpanConsumer.prefixWithTimestampMillis;

public class ElasticsearchHttpSpanConsumerTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();
  @Rule
  public MockWebServer es = new MockWebServer();

  ElasticsearchHttpStorage storage = ElasticsearchHttpStorage.builder()
      .hosts(asList(es.url("").toString()))
      .build();

  /** gets the index template so that each test doesn't have to */
  @Before
  public void ensureIndexTemplate() throws IOException, InterruptedException {
    es.enqueue(new MockResponse().setBody("{\"version\":{\"number\":\"2.4.0\"}}"));
    es.enqueue(new MockResponse()); // get template
    storage.ensureIndexTemplate();
    es.takeRequest(); // get version
    es.takeRequest(); // get template
  }

  @After
  public void close() throws IOException {
    storage.close();
  }

  @Test
  public void addsTimestamp_millisIntoJson() throws Exception {
    es.enqueue(new MockResponse());

    Span span = Span.builder().traceId(20L).id(20L).name("get")
        .timestamp(TODAY * 1000).build();

    accept(span);

    assertThat(es.takeRequest().getBody().readUtf8())
        .contains("\n{\"timestamp_millis\":" + Long.toString(TODAY) + ",\"traceId\":");
  }

  @Test
  public void prefixWithTimestampMillis_readable() throws Exception {
    Span span = Span.builder().traceId(20L).id(20L).name("get")
        .timestamp(TODAY * 1000).build();

    byte[] document = prefixWithTimestampMillis(Codec.JSON.writeSpan(span), span.timestamp);
    assertThat(Codec.JSON.readSpan(document))
        .isEqualTo(span); // ignores timestamp_millis field
  }

  @Test
  public void doesntWriteSpanId() throws Exception {
    es.enqueue(new MockResponse());

    accept(TestObjects.LOTS_OF_SPANS[0]);

    RecordedRequest request = es.takeRequest();
    assertThat(request.getBody().readByteString().utf8())
        .doesNotContain("\"_type\":\"span\",\"_id\"");
  }

  @Test
  public void writesSpanNaturallyWhenNoTimestamp() throws Exception {
    es.enqueue(new MockResponse());

    Span span = Span.builder().traceId(1L).id(1L).name("foo").build();
    accept(span);

    assertThat(es.takeRequest().getBody().readByteString().utf8())
        .contains("\n" + new String(Codec.JSON.writeSpan(span), UTF_8) + "\n");
  }

  @Test
  public void indexesServiceSpan_explicitTimestamp() throws Exception {
    es.enqueue(new MockResponse());

    Span span = TestObjects.TRACE.get(0);
    accept(span);

    assertThat(es.takeRequest().getBody().readByteString().utf8()).endsWith(
        "\"_type\":\"servicespan\",\"_id\":\"web|get\"}}\n"
            + "{\"serviceName\":\"web\",\"spanName\":\"get\"}\n"
    );
  }

  @Test
  public void indexesServiceSpan_multipleServices() throws Exception {
    es.enqueue(new MockResponse());

    Span span = TestObjects.TRACE.get(1);
    accept(span);

    assertThat(es.takeRequest().getBody().readByteString().utf8())
        .contains(
            "\"_type\":\"servicespan\",\"_id\":\"app|get\"}}\n"
                + "{\"serviceName\":\"app\",\"spanName\":\"get\"}\n"
        )
        .contains(
            "\"_type\":\"servicespan\",\"_id\":\"web|get\"}}\n"
                + "{\"serviceName\":\"web\",\"spanName\":\"get\"}\n"
        );
  }

  @Test
  public void indexesServiceSpan_basedOnGuessTimestamp() throws Exception {
    es.enqueue(new MockResponse());

    Annotation cs = Annotation.create(
        TimeUnit.DAYS.toMicros(365), // 1971-01-01
        CLIENT_SEND,
        TestObjects.APP_ENDPOINT
    );

    Span span = Span.builder().traceId(1L).id(1L).name("s").addAnnotation(cs).build();

    // sanity check data
    assertThat(span.timestamp).isNull();
    assertThat(guessTimestamp(span)).isNotNull();

    accept(span);

    // index timestamp is the server timestamp, not current time!
    assertThat(es.takeRequest().getBody().readByteString().utf8()).contains(
        "{\"index\":{\"_index\":\"zipkin-1971-01-01\",\"_type\":\"span\"}}\n",
        "{\"index\":{\"_index\":\"zipkin-1971-01-01\",\"_type\":\"servicespan\",\"_id\":\"app|s\"}}\n"
    );
  }

  @Test
  public void indexesServiceSpan_basedOnAnnotationTimestamp() throws Exception {
    es.enqueue(new MockResponse());

    Annotation sr = Annotation.create(
        TimeUnit.DAYS.toMicros(365), // 1971-01-01
        SERVER_RECV,
        TestObjects.APP_ENDPOINT
    );

    Span span = Span.builder().traceId(1L).id(2L).parentId(1L).name("s").addAnnotation(sr).build();

    // sanity check data
    assertThat(span.timestamp).isNull();
    assertThat(guessTimestamp(span)).isNull();

    accept(span);

    // index timestamp is the server timestamp, not current time!
    assertThat(es.takeRequest().getBody().readByteString().utf8()).contains(
        "{\"index\":{\"_index\":\"zipkin-1971-01-01\",\"_type\":\"span\"}}\n",
        "{\"index\":{\"_index\":\"zipkin-1971-01-01\",\"_type\":\"servicespan\",\"_id\":\"app|s\"}}\n"
    );
  }

  @Test
  public void indexesServiceSpan_currentTimestamp() throws Exception {
    es.enqueue(new MockResponse());

    Span span = Span.builder().traceId(1L).id(2L).parentId(1L).name("s")
        .addBinaryAnnotation(BinaryAnnotation.create("f", "", TestObjects.APP_ENDPOINT))
        .build();

    // sanity check data
    assertThat(span.timestamp).isNull();
    assertThat(guessTimestamp(span)).isNull();

    accept(span);

    String today = storage.indexNameFormatter().indexNameForTimestamp(TODAY);
    assertThat(es.takeRequest().getBody().readByteString().utf8()).contains(
        "{\"index\":{\"_index\":\"" + today + "\",\"_type\":\"span\"}}\n",
        "{\"index\":{\"_index\":\"" + today + "\",\"_type\":\"servicespan\",\"_id\":\"app|s\"}}\n"
    );
  }

  @Test
  public void addsPipelineId() throws Exception {
    close();

    storage = ElasticsearchHttpStorage.builder()
        .hosts(asList(es.url("").toString()))
        .pipeline("zipkin")
        .build();
    ensureIndexTemplate();

    es.enqueue(new MockResponse());

    accept(TestObjects.TRACE.get(0));

    RecordedRequest request = es.takeRequest();
    assertThat(request.getPath())
        .isEqualTo("/_bulk?pipeline=zipkin");
  }

  void accept(Span span) throws Exception {
    CallbackCaptor<Void> callback = new CallbackCaptor<>();
    storage.asyncSpanConsumer().accept(asList(span), callback);
    callback.get();
  }
}
