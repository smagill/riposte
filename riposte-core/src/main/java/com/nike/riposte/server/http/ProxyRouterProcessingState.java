package com.nike.riposte.server.http;

import com.nike.internal.util.Pair;
import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient;
import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.StreamingChannel;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.wingtips.Span;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import static com.nike.riposte.util.AsyncNettyHelper.extractTracingAndMdcInfoFromChannelHandlerContext;
import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;

/**
 * A class holding state context for a proxy router request. Setup/teardown handlers can call {@link
 * #cleanStateForNewRequest()} for each new incoming request to make sure there's never any stale state from a previous
 * request for this connection.
 *
 * @author Nic Munroe
 */
public class ProxyRouterProcessingState implements ProcessingState {

    private static final Logger logger = LoggerFactory.getLogger(ProxyRouterProcessingState.class);
    
    private StreamingAsyncHttpClient.StreamingCallback streamingCallback;
    private CompletableFuture<StreamingChannel> streamingChannelCompletableFuture;
    // TODO: This business with first chunk future, latest chunk future, and ordering is kinda gross. Find a cleaner solution.
    private CompletableFuture<StreamingChannel> firstChunkCF;
    private CompletableFuture<StreamingChannel> latestChunkCF;
    private long streamingStartTimeNanos;
    private boolean requestStreamingCompletedSuccessfully;
    private boolean requestStreamingCancelled;

    private HttpRequest proxyHttpRequest;
    private HttpResponse proxyHttpResponse;
    private Throwable proxyError;

    private DistributedTracingConfig<Span> distributedTracingConfig;
    private boolean tracingResponseTaggingAndFinalSpanNameCompleted = false;

    public ProxyRouterProcessingState() {
        // Default constructor - do nothing
    }

    public void cleanStateForNewRequest() {
        streamingCallback = null;
        streamingChannelCompletableFuture = null;
        firstChunkCF = null;
        latestChunkCF = null;
        streamingStartTimeNanos = 0;
        requestStreamingCompletedSuccessfully = false;
        requestStreamingCancelled = false;
        proxyHttpRequest = null;
        proxyHttpResponse = null;
        proxyError = null;
        distributedTracingConfig = null;
        tracingResponseTaggingAndFinalSpanNameCompleted = false;
    }

    public CompletableFuture<StreamingChannel> getStreamingChannelCompletableFuture() {
        return streamingChannelCompletableFuture;
    }

    public void setStreamingChannelCompletableFuture(
        CompletableFuture<StreamingChannel> streamingChannelCompletableFuture) {
        this.streamingChannelCompletableFuture = streamingChannelCompletableFuture;
    }

    public StreamingAsyncHttpClient.StreamingCallback getStreamingCallback() {
        return streamingCallback;
    }

    public void setStreamingCallback(StreamingAsyncHttpClient.StreamingCallback streamingCallback) {
        this.streamingCallback = streamingCallback;
    }

    @SuppressWarnings("WeakerAccess")
    protected synchronized void initFirstChunkCompletableFutureIfNecessary() {
        if (firstChunkCF == null) {
            firstChunkCF = new CompletableFuture<>();
            latestChunkCF = firstChunkCF;
        }
    }

    public void triggerChunkProcessing(StreamingChannel sc) {
        initFirstChunkCompletableFutureIfNecessary();
        firstChunkCF.complete(sc);
    }

    protected void triggerStreamingChannelErrorForChunks(Throwable cause) {
        initFirstChunkCompletableFutureIfNecessary();
        firstChunkCF.completeExceptionally(cause);
    }

    public void registerStreamingChannelChunkProcessingAction(
        BiConsumer<? super StreamingChannel, ? super Throwable> action) {
        initFirstChunkCompletableFutureIfNecessary();
        latestChunkCF = latestChunkCF.whenComplete(action);
    }

    public void setStreamingStartTimeNanos(long streamingStartTimeNanos) {
        this.streamingStartTimeNanos = streamingStartTimeNanos;
    }

    public long getStreamingStartTimeNanos() {
        return streamingStartTimeNanos;
    }

    public boolean isRequestStreamingCompletedSuccessfully() {
        return this.requestStreamingCompletedSuccessfully;
    }

    public void setRequestStreamingCompletedSuccessfully() {
        this.requestStreamingCompletedSuccessfully = true;
    }

    public boolean isRequestStreamingCancelled() {
        return requestStreamingCancelled;
    }

    public void cancelRequestStreaming(Throwable reason, ChannelHandlerContext ctx) {
        if (this.requestStreamingCancelled || this.requestStreamingCompletedSuccessfully) {
            // We don't need to do anything here because request streaming was either already cancelled,
            //      or it completed successfully.
            return;
        }

        // This is the first time this method has been called, so set everything up for a failed request streaming.
        this.requestStreamingCancelled = true;

        boolean firstChunkExisted = (firstChunkCF != null);

        // Trigger unwinding of any backed-up chunks. This call to triggerStreamingChannelErrorForChunks() may do
        //      nothing if the chunk streaming had already started, but that's ok. The important thing is that the
        //      CompletableFuture controlling the chunk stream is started *at some point* so that chunk resources
        //      can be released.
        triggerStreamingChannelErrorForChunks(reason);

        // Log why the request streaming was cancelled for debugging purposes, but only if the first chunk existed.
        //      If the first chunk didn't exist, then this was most likely not a proxy/router request so no need to
        //      clutter logs.
        if (firstChunkExisted) {
            Pair<Deque<Span>, Map<String, String>> tracingInfo = extractTracingAndMdcInfoFromChannelHandlerContext(ctx);
            runnableWithTracingAndMdc(
                () -> logger.info("The proxied request's chunk-streaming was cancelled. cancel_reason=\"{}\"",
                                  reason.toString()),
                tracingInfo
            ).run();
        }
    }

    public void cancelDownstreamRequest(Throwable reason) {
        if (streamingCallback != null)
            streamingCallback.cancelStreamingToOriginalCaller();

        // Do nothing else if a StreamingChannel was never started.
        if (streamingChannelCompletableFuture == null)
            return;

        streamingChannelCompletableFuture.whenComplete((sc, error) -> {
            if (sc != null) {
                // A StreamingChannel was created. Tell it to stop if it's still going.
                if (sc.isDownstreamCallActive())
                    sc.closeChannelDueToUnrecoverableError(reason);
            }
        });
    }

    public HttpRequest getProxyHttpRequest() {
        return proxyHttpRequest;
    }

    public void setProxyHttpRequest(HttpRequest proxyHttpRequest) {
        this.proxyHttpRequest = proxyHttpRequest;
    }

    public HttpResponse getProxyHttpResponse() {
        return proxyHttpResponse;
    }

    public void setProxyHttpResponse(HttpResponse proxyHttpResponse) {
        this.proxyHttpResponse = proxyHttpResponse;
    }

    public Throwable getProxyError() {
        return proxyError;
    }

    public void setProxyError(Throwable proxyError) {
        this.proxyError = proxyError;
    }

    public boolean isTracingResponseTaggingAndFinalSpanNameCompleted() {
        return tracingResponseTaggingAndFinalSpanNameCompleted;
    }
    
    /**
     * DO NOT CALL THIS! It is here temporarily for internal use and will likely go away. You shouldn't be changing
     * {@link DistributedTracingConfig} here anyway - use {@link ServerConfig#distributedTracingConfig()}.
     *
     * @deprecated Don't call this yourself - set your server's distributed tracing config via
     * {@link ServerConfig#distributedTracingConfig()}
     */
    @Deprecated
    public void setDistributedTracingConfig(
        DistributedTracingConfig<Span> distributedTracingConfig
    ) {
        this.distributedTracingConfig = distributedTracingConfig;
    }

    public void handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone(Span spanAroundProxyCall) {
        if (
            tracingResponseTaggingAndFinalSpanNameCompleted
            || (distributedTracingConfig == null)
            || (spanAroundProxyCall == null)
        ) {
            return;
        }

        tracingResponseTaggingAndFinalSpanNameCompleted = true;

        try {
            distributedTracingConfig
                .getProxyRouterSpanNamingAndTaggingStrategy()
                .handleResponseTaggingAndFinalSpanName(
                    spanAroundProxyCall,
                    getProxyHttpRequest(),
                    getProxyHttpResponse(),
                    getProxyError()
                );
        }
        catch (Throwable t) {
            logger.error(
                "Unexpected error occurred while trying to set final span name and proxy response tagging. This "
                + "exception will be ignored, but should be investigated - it should not happen.",
                t
            );
        }
    }
}
