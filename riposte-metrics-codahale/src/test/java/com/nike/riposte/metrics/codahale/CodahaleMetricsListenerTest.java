package com.nike.riposte.metrics.codahale;

import com.nike.riposte.metrics.codahale.impl.EndpointMetricsHandlerDefaultImpl;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.ResponseSender;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.metrics.ServerMetricsEvent;
import com.nike.riposte.util.Matcher;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;
import org.slf4j.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;

import static com.codahale.metrics.MetricRegistry.name;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link CodahaleMetricsListener}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class CodahaleMetricsListenerTest {

    private CodahaleMetricsListener listener;
    private CodahaleMetricsCollector cmcMock;
    private EndpointMetricsHandler endpointMetricsHandlerMock;
    private MetricRegistry metricRegistryMock;

    private Map<String, Timer> registeredTimerMocks;
    private Map<String, Meter> registeredMeterMocks;
    private Map<String, Counter> registeredCounterMocks;
    private Map<String, Histogram> registeredHistogramMocks;

    private Map<String, Gauge> registeredGauges;

    private ServerConfig serverConfig;

    private HttpProcessingState state;

    private RequestInfo<?> requestInfoMock;
    private ResponseInfo<?> responseInfoMock;

    private Instant requestStartTime;

    @Before
    public void beforeMethod() {
        setupMetricRegistryAndCodahaleMetricsCollector();

        endpointMetricsHandlerMock = mock(EndpointMetricsHandler.class);
        listener = new CodahaleMetricsListener(cmcMock, endpointMetricsHandlerMock, true);

        serverConfig = new ServerConfig() {
            private final List<Endpoint<?>> endpoints = Arrays.asList(
                new DummyEndpoint(Matcher.match("/foo")),
                new DummyEndpoint(Matcher.match("/bar", HttpMethod.POST, HttpMethod.PUT)),
                new DummyEndpoint(Matcher.multiMatch(Arrays.asList("/multiFoo", "/multiBar"))),
                new DummyEndpoint(Matcher.multiMatch(Arrays.asList("/multiBaz", "/multiBat"),
                                                     HttpMethod.PATCH, HttpMethod.OPTIONS))
            );

            @Override
            public Collection<Endpoint<?>> appEndpoints() {
                return endpoints;
            }

            @Override
            public int numBossThreads() {
                return 3;
            }

            @Override
            public int numWorkerThreads() {
                return 42;
            }

            @Override
            public int maxRequestSizeInBytes() {
                return 42434445;
            }
        };
        listener.initEndpointAndServerConfigMetrics(serverConfig);

        requestInfoMock = mock(RequestInfo.class);
        responseInfoMock = mock(ResponseInfo.class);

        state = new HttpProcessingState();

        state.setRequestInfo(requestInfoMock);
        state.setResponseInfo(responseInfoMock);
        requestStartTime = Instant.now().minus(42, ChronoUnit.MILLIS);
        state.setRequestStartTime(requestStartTime);
    }

    private void setupMetricRegistryAndCodahaleMetricsCollector() {
        metricRegistryMock = mock(MetricRegistry.class);
        cmcMock = mock(CodahaleMetricsCollector.class);

        doReturn(metricRegistryMock).when(cmcMock).getMetricRegistry();

        registeredTimerMocks = new HashMap<>();
        doAnswer(invocation -> {
            String name = invocation.getArgumentAt(0, String.class);
            Timer timerMock = mock(Timer.class);
            registeredTimerMocks.put(name, timerMock);
            return timerMock;
        }).when(metricRegistryMock).timer(anyString());

        registeredMeterMocks = new HashMap<>();
        doAnswer(invocation -> {
            String name = invocation.getArgumentAt(0, String.class);
            Meter meterMock = mock(Meter.class);
            registeredMeterMocks.put(name, meterMock);
            return meterMock;
        }).when(metricRegistryMock).meter(anyString());

        registeredCounterMocks = new HashMap<>();
        doAnswer(invocation -> {
            String name = invocation.getArgumentAt(0, String.class);
            Counter counterMock = mock(Counter.class);
            registeredCounterMocks.put(name, counterMock);
            return counterMock;
        }).when(metricRegistryMock).counter(anyString());

        registeredHistogramMocks = new HashMap<>();
        doAnswer(invocation -> {
            String name = invocation.getArgumentAt(0, String.class);
            Histogram histogramMock = mock(Histogram.class);
            registeredHistogramMocks.put(name, histogramMock);
            return histogramMock;
        }).when(metricRegistryMock).histogram(anyString());

        registeredGauges = new HashMap<>();
        doAnswer(invocation -> {
            String name = invocation.getArgumentAt(0, String.class);
            Gauge gauge = invocation.getArgumentAt(1, Gauge.class);
            registeredGauges.put(name, gauge);
            return gauge;
        }).when(metricRegistryMock).register(anyString(), any(Metric.class));
    }

    @Test
    public void single_arg_constructor_sets_fields_as_expected() {
        // given
        setupMetricRegistryAndCodahaleMetricsCollector();

        // when
        CodahaleMetricsListener instance = new CodahaleMetricsListener(cmcMock);

        // then
        verifyServerStatisticMetrics(instance);
        assertThat(instance.getMetricsCollector()).isSameAs(cmcMock);
        assertThat(instance.metricsCollector).isSameAs(cmcMock);
        assertThat(instance.endpointMetricsHandler)
            .isNotNull()
            .isInstanceOf(EndpointMetricsHandlerDefaultImpl.class);
        assertThat(instance.getEndpointMetricsHandler()).isSameAs(instance.endpointMetricsHandler);
        assertThat(instance.includeServerConfigMetrics).isFalse();
        assertThat(registeredGauges).isEmpty();
    }

    @Test
    public void double_arg_constructor_sets_fields_as_expected() {
        // given
        setupMetricRegistryAndCodahaleMetricsCollector();

        // when
        CodahaleMetricsListener instance = new CodahaleMetricsListener(cmcMock, endpointMetricsHandlerMock);

        // then
        verifyServerStatisticMetrics(instance);
        assertThat(instance.getMetricsCollector()).isSameAs(cmcMock);
        assertThat(instance.metricsCollector).isSameAs(cmcMock);
        assertThat(instance.getEndpointMetricsHandler()).isSameAs(endpointMetricsHandlerMock);
        assertThat(instance.endpointMetricsHandler).isSameAs(endpointMetricsHandlerMock);
        assertThat(instance.includeServerConfigMetrics).isFalse();
        assertThat(registeredGauges).isEmpty();
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void kitchen_sink_constructor_sets_fields_as_expected(boolean includeServerConfigMetrics) {
        // given
        setupMetricRegistryAndCodahaleMetricsCollector();

        // when
        CodahaleMetricsListener instance = new CodahaleMetricsListener(cmcMock, endpointMetricsHandlerMock, includeServerConfigMetrics);

        // then
        verifyServerStatisticMetrics(instance);
        assertThat(instance.getMetricsCollector()).isSameAs(cmcMock);
        assertThat(instance.metricsCollector).isSameAs(cmcMock);
        assertThat(instance.getEndpointMetricsHandler()).isSameAs(endpointMetricsHandlerMock);
        assertThat(instance.endpointMetricsHandler).isSameAs(endpointMetricsHandlerMock);
        assertThat(instance.includeServerConfigMetrics).isEqualTo(includeServerConfigMetrics);
        assertThat(registeredGauges).isEmpty();
    }

    private void verifyServerStatisticMetrics(CodahaleMetricsListener instance) {
        assertThat(instance.getInflightRequests()).isSameAs(instance.inflightRequests);
        verify(metricRegistryMock).counter(name(instance.prefix, "inflight-requests"));
        assertThat(instance.inflightRequests).isSameAs(registeredCounterMocks.get(name(instance.prefix, "inflight-requests")));

        assertThat(instance.getProcessedRequests()).isSameAs(instance.processedRequests);
        verify(metricRegistryMock).counter(name(instance.prefix, "processed-requests"));
        assertThat(instance.processedRequests).isSameAs(registeredCounterMocks.get(name(instance.prefix, "processed-requests")));

        assertThat(instance.getFailedRequests()).isSameAs(instance.failedRequests);
        verify(metricRegistryMock).counter(name(instance.prefix, "failed-requests"));
        assertThat(instance.failedRequests).isSameAs(registeredCounterMocks.get(name(instance.prefix, "failed-requests")));

        assertThat(instance.getResponseWriteFailed()).isSameAs(instance.responseWriteFailed);
        verify(metricRegistryMock).counter(name(instance.prefix, "response-write-failed"));
        assertThat(instance.responseWriteFailed).isSameAs(registeredCounterMocks.get(name(instance.prefix, "response-write-failed")));

        assertThat(instance.getResponseSizes()).isSameAs(instance.responseSizes);
        verify(metricRegistryMock).histogram(name(instance.prefix, "response-sizes"));
        assertThat(instance.responseSizes).isSameAs(registeredHistogramMocks.get(name(instance.prefix, "response-sizes")));

        assertThat(instance.getRequestSizes()).isSameAs(instance.requestSizes);
        verify(metricRegistryMock).histogram(name(instance.prefix, "request-sizes"));
        assertThat(instance.requestSizes).isSameAs(registeredHistogramMocks.get(name(instance.prefix, "request-sizes")));
    }

    @Test
    public void constructor_throws_IllegalArgumentException_if_passed_null() {
        // when
        Throwable ex = catchThrowable(() -> new CodahaleMetricsListener(null));

        // then
        assertThat(ex).isInstanceOf(IllegalArgumentException.class);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void initServerConfigMetrics_adds_expected_metrics(boolean includeServerConfigMetrics) {
        // given
        setupMetricRegistryAndCodahaleMetricsCollector();
        CodahaleMetricsListener instance = new CodahaleMetricsListener(cmcMock,
                                                                       endpointMetricsHandlerMock,
                                                                       includeServerConfigMetrics);
        verifyServerStatisticMetrics(instance);

        String expectedBossThreadsGaugeName = name(ServerConfig.class.getSimpleName(), "bossThreads");
        String expectedWorkerThreadsGaugeName = name(ServerConfig.class.getSimpleName(), "workerThreads");
        String expectedMaxRequestSizeInBytesGaugeName = name(ServerConfig.class.getSimpleName(), "maxRequestSizeInBytes");
        String expectedEndpointsListGaugeName = name(ServerConfig.class.getSimpleName(), "endpoints");

        List<String> expectedEndpointsListValue =
            serverConfig.appEndpoints().stream()
                        .map(
                            endpoint -> endpoint.getClass().getName()
                                        + "-" + instance.getMatchingHttpMethodsAsCombinedString(endpoint)
                                        + "-" + endpoint.requestMatcher().matchingPathTemplates()
                        )
                        .collect(Collectors.toList());

        // when
        instance.initEndpointAndServerConfigMetrics(serverConfig);

        // then
        if (includeServerConfigMetrics) {
            // Metrics for server config values
            assertThat(registeredGauges).containsKey(expectedBossThreadsGaugeName);
            assertThat(registeredGauges.get(expectedBossThreadsGaugeName).getValue())
                .isEqualTo(serverConfig.numBossThreads());

            assertThat(registeredGauges).containsKey(expectedWorkerThreadsGaugeName);
            assertThat(registeredGauges.get(expectedWorkerThreadsGaugeName).getValue())
                .isEqualTo(serverConfig.numWorkerThreads());

            assertThat(registeredGauges).containsKey(expectedMaxRequestSizeInBytesGaugeName);
            assertThat(registeredGauges.get(expectedMaxRequestSizeInBytesGaugeName).getValue())
                .isEqualTo(serverConfig.maxRequestSizeInBytes());

            assertThat(registeredGauges).containsKey(expectedEndpointsListGaugeName);
            assertThat(registeredGauges.get(expectedEndpointsListGaugeName).getValue())
                .isEqualTo(expectedEndpointsListValue);
        }
        else {
            // No server config values should have been registered.
            verifyNoMoreInteractions(metricRegistryMock);
        }

        // In either case, the EndpointMetricsHandler should have been called to delegate setting up endpoint-specific metrics.
        verify(endpointMetricsHandlerMock).setupEndpointsMetrics(serverConfig, metricRegistryMock);
    }

    @Test
    public void onEvent_works_as_expected_for_REQUEST_RECEIVED() {
        // given
        ServerMetricsEvent event = ServerMetricsEvent.REQUEST_RECEIVED;

        // when
        listener.onEvent(event, null);

        // then
        verify(listener.inflightRequests).inc();
    }

    @Test
    public void onEvent_works_as_expected_for_RESPONSE_WRITE_FAILED() {
        // given
        ServerMetricsEvent event = ServerMetricsEvent.RESPONSE_WRITE_FAILED;

        // when
        listener.onEvent(event, null);

        // then
        verify(listener.responseWriteFailed).inc();
    }

    @DataProvider(value = {
        "GET    |   99",
        "GET    |   142",
        "GET    |   242",
        "GET    |   342",
        "GET    |   404",
        "GET    |   405",
        "GET    |   442",
        "GET    |   500",
        "GET    |   542",
        "GET    |   600",
        "POST   |   99",
        "POST   |   142",
        "POST   |   242",
        "POST   |   342",
        "POST   |   404",
        "POST   |   405",
        "POST   |   442",
        "POST   |   500",
        "POST   |   542",
        "POST   |   600",
        "PUT    |   99",
        "PUT    |   142",
        "PUT    |   242",
        "PUT    |   342",
        "PUT    |   404",
        "PUT    |   405",
        "PUT    |   442",
        "PUT    |   500",
        "PUT    |   542",
        "PUT    |   600",
        "DELETE |   99",
        "DELETE |   142",
        "DELETE |   242",
        "DELETE |   342",
        "DELETE |   404",
        "DELETE |   405",
        "DELETE |   442",
        "DELETE |   500",
        "DELETE |   542",
        "DELETE |   600",
        "PATCH  |   99",
        "PATCH  |   142",
        "PATCH  |   242",
        "PATCH  |   342",
        "PATCH  |   404",
        "PATCH  |   405",
        "PATCH  |   442",
        "PATCH  |   500",
        "PATCH  |   542",
        "PATCH  |   600",
        "null   |   99",
        "null   |   142",
        "null   |   242",
        "null   |   342",
        "null   |   404",
        "null   |   405",
        "null   |   442",
        "null   |   500",
        "null   |   542",
        "null   |   600"
    }, splitBy = "\\|")
    @Test
    public void onEvent_works_as_expected_for_RESPONSE_SENT_with_endpoint(
        String requestMethodStr, int responseStatusCode
    ) throws InterruptedException {
        // given
        ServerMetricsEvent event = ServerMetricsEvent.RESPONSE_SENT;

        HttpMethod requestMethod = (requestMethodStr == null) ? null : HttpMethod.valueOf(requestMethodStr);
        doReturn(requestMethod).when(requestInfoMock).getMethod();

        Endpoint<?> endpoint = serverConfig.appEndpoints().iterator().next();
        state.setEndpointForExecution(endpoint);

        doReturn(responseStatusCode).when(responseInfoMock).getHttpStatusCodeWithDefault(ResponseSender.DEFAULT_HTTP_STATUS_CODE);

        int requestRawContentLengthBytes = (int)(Math.random() * 10000);
        doReturn(requestRawContentLengthBytes).when(requestInfoMock).getRawContentLengthInBytes();

        long finalResponseContentLength = (long)(Math.random() * 10000);
        doReturn(finalResponseContentLength).when(responseInfoMock).getFinalContentLength();

        Thread.sleep((long)(Math.random() * 25));

        // when
        long beforeCallTime = System.currentTimeMillis();
        listener.onEvent(event, state);
        long afterCallTime = System.currentTimeMillis();

        // then
        // Inflight requests counter decremented
        verify(listener.inflightRequests).dec();
        // Processed requests counter incremented
        verify(listener.processedRequests).inc();

        // If response code is greater than or equal to 400, then the failed requests counter should be incremented.
        if (responseStatusCode >= 400)
            verify(listener.failedRequests).inc();

        // Request and response size histograms should be updated with the relevant values from the request and response.
        verify(listener.requestSizes).update(requestRawContentLengthBytes);
        verify(listener.responseSizes).update(finalResponseContentLength);

        // The EndpointMetricsHandler should have been notified
        int responseHttpStatusCodeXXValue = responseStatusCode / 100;
        long expectedElapsedTimeMillisLowerBound = beforeCallTime - requestStartTime.toEpochMilli();
        long expectedElapsedTimeMillisUpperBound = afterCallTime - requestStartTime.toEpochMilli();
        ArgumentCaptor<Long> elapsedTimeMillisArgCaptor = ArgumentCaptor.forClass(Long.class);
        verify(endpointMetricsHandlerMock).handleRequest(
            eq(requestInfoMock), eq(responseInfoMock), eq(state), eq(responseStatusCode),
            eq(responseHttpStatusCodeXXValue), elapsedTimeMillisArgCaptor.capture()
        );
        assertThat(elapsedTimeMillisArgCaptor.getValue())
            .isBetween(expectedElapsedTimeMillisLowerBound, expectedElapsedTimeMillisUpperBound);
    }

    @DataProvider(value = {
        "200",
        "404",
        "405",
        "500"
    })
    @Test
    public void onEvent_works_as_expected_for_RESPONSE_SENT_without_endpoint(int responseStatusCode) {
        // Should work the same as the onEvent_works_as_expected_for_RESPONSE_SENT_with_endpoint test above
        //      as far as CodahaleMetricsListener is concerned - it's the EndpointMetricsHandler's responsibility
        //      to deal with any intricacies around this use case.

        // given
        ServerMetricsEvent event = ServerMetricsEvent.RESPONSE_SENT;
        state.setEndpointForExecution(null);
        doReturn(responseStatusCode).when(responseInfoMock).getHttpStatusCodeWithDefault(ResponseSender.DEFAULT_HTTP_STATUS_CODE);

        int requestRawContentLengthBytes = (int)(Math.random() * 10000);
        doReturn(requestRawContentLengthBytes).when(requestInfoMock).getRawContentLengthInBytes();

        long finalResponseContentLength = (long)(Math.random() * 10000);
        doReturn(finalResponseContentLength).when(responseInfoMock).getFinalContentLength();

        // when
        long beforeCallTime = System.currentTimeMillis();
        listener.onEvent(event, state);
        long afterCallTime = System.currentTimeMillis();

        // then
        // Inflight requests counter decremented
        verify(listener.inflightRequests).dec();
        // Processed requests counter incremented
        verify(listener.processedRequests).inc();

        // If response code is greater than or equal to 400, then the failed requests counter should be incremented.
        if (responseStatusCode >= 400)
            verify(listener.failedRequests).inc();

        // Request and response size histograms should be updated with the relevant values from the request and response.
        verify(listener.requestSizes).update(requestRawContentLengthBytes);
        verify(listener.responseSizes).update(finalResponseContentLength);

        // The EndpointMetricsHandler should have been notified
        int responseHttpStatusCodeXXValue = responseStatusCode / 100;
        long expectedElapsedTimeMillisLowerBound = beforeCallTime - requestStartTime.toEpochMilli();
        long expectedElapsedTimeMillisUpperBound = afterCallTime - requestStartTime.toEpochMilli();
        ArgumentCaptor<Long> elapsedTimeMillisArgCaptor = ArgumentCaptor.forClass(Long.class);
        verify(endpointMetricsHandlerMock).handleRequest(
            eq(requestInfoMock), eq(responseInfoMock), eq(state), eq(responseStatusCode),
            eq(responseHttpStatusCodeXXValue), elapsedTimeMillisArgCaptor.capture()
        );
        assertThat(elapsedTimeMillisArgCaptor.getValue())
            .isBetween(expectedElapsedTimeMillisLowerBound, expectedElapsedTimeMillisUpperBound);
    }

    @Test
    public void onEvent_should_do_nothing_if_passed_RESPONSE_SENT_with_value_object_that_is_not_a_HttpProcessingState() {
        // when
        listener.onEvent(ServerMetricsEvent.RESPONSE_SENT, new Object());

        // then
        verifyZeroInteractions(listener.inflightRequests, listener.processedRequests);
        verify(endpointMetricsHandlerMock, never()).handleRequest(
            any(RequestInfo.class), any(ResponseInfo.class), any(HttpProcessingState.class), anyInt(), anyInt(), anyLong()
        );
    }

    @Test
    public void onEvent_should_short_circuit_for_RESPONSE_SENT_if_response_info_is_null() {
        // given
        state.setResponseInfo(null);

        // when
        listener.onEvent(ServerMetricsEvent.RESPONSE_SENT, state);

        // then
        // Inflight requests and processed requests counters should still be adjusted properly
        verify(listener.inflightRequests).dec();
        verify(listener.processedRequests).inc();
        // But we should short circuit immediately afterward
        verifyZeroInteractions(listener.requestSizes, listener.responseSizes);
        verify(endpointMetricsHandlerMock, never()).handleRequest(
            any(RequestInfo.class), any(ResponseInfo.class), any(HttpProcessingState.class), anyInt(), anyInt(), anyLong()
        );
    }

    @Test
    public void onEvent_should_short_circuit_for_RESPONSE_SENT_if_request_start_time_is_null() {
        // given
        state.setRequestStartTime(null);

        // when
        listener.onEvent(ServerMetricsEvent.RESPONSE_SENT, state);

        // then
        // Inflight requests and processed requests counters should still be adjusted properly
        verify(listener.inflightRequests).dec();
        verify(listener.processedRequests).inc();
        // But we should short circuit immediately afterward
        verifyZeroInteractions(listener.requestSizes, listener.responseSizes);
        verify(endpointMetricsHandlerMock, never()).handleRequest(
            any(RequestInfo.class), any(ResponseInfo.class), any(HttpProcessingState.class), anyInt(), anyInt(), anyLong()
        );
    }

    @Test
    public void onEvent_for_RESPONSE_SENT_updates_responseSizes_histogram_with_0_if_getFinalContentLength_is_null() {
        // given
        doReturn(null).when(responseInfoMock).getFinalContentLength();

        // when
        listener.onEvent(ServerMetricsEvent.RESPONSE_SENT, state);

        // then
        verify(listener.responseSizes).update(0L);
    }

    @Test
    public void onEvent_does_nothing_if_event_type_is_unknown() {
        // given
        ServerMetricsEvent event = null;
        Logger loggerMock = mock(Logger.class);
        doReturn(false).when(loggerMock).isDebugEnabled();
        Whitebox.setInternalState(listener, "logger", loggerMock);

        // when
        listener.onEvent(event, null);

        // then
        verifyZeroInteractions(listener.inflightRequests, listener.responseWriteFailed, listener.processedRequests);
        verify(loggerMock).error("Metrics Error: unknown metrics event " + event);
    }

    @Test
    public void onEvent_gracefully_handles_thrown_exceptions() {
        // given
        ServerMetricsEvent event = ServerMetricsEvent.RESPONSE_SENT;
        RuntimeException ex = new RuntimeException("kaboom");
        doThrow(ex).when(listener.inflightRequests).dec();
        Logger loggerMock = mock(Logger.class);
        doReturn(false).when(loggerMock).isDebugEnabled();
        Whitebox.setInternalState(listener, "logger", loggerMock);

        // when
        listener.onEvent(event, state);

        // then
        verifyZeroInteractions(listener.processedRequests); // Should have blown up before the processedRequests stuff.
        verify(loggerMock).error("Metrics Error: ", ex);
    }

    @Test
    public void getMetricRegistry_delegates_to_metricsCollector() {
        // given
        MetricRegistry mrMock = mock(MetricRegistry.class);
        doReturn(mrMock).when(cmcMock).getMetricRegistry();

        // when
        MetricRegistry result = listener.getMetricRegistry();

        // then
        assertThat(result).isSameAs(mrMock);
    }

    @Test
    public void code_coverage_hoops() {
        // jump!
        // Account for the logger.isDebugEnabled() branches.
        Logger loggerMock = mock(Logger.class);
        doReturn(false).when(loggerMock).isDebugEnabled();
        Whitebox.setInternalState(listener, "logger", loggerMock);
        listener.onEvent(ServerMetricsEvent.REQUEST_RECEIVED, null);
        listener.onEvent(ServerMetricsEvent.RESPONSE_SENT, state);
    }

    private static class DummyEndpoint extends StandardEndpoint<Void, Void> {

        private final Matcher matcher;

        public DummyEndpoint(Matcher matcher) {
            this.matcher = matcher;
        }

        @Override
        public Matcher requestMatcher() {
            return matcher;
        }

        @Override
        public CompletableFuture<ResponseInfo<Void>> execute(RequestInfo<Void> request,
                                                             Executor longRunningTaskExecutor,
                                                             ChannelHandlerContext ctx) {
            return null;
        }
    }
}