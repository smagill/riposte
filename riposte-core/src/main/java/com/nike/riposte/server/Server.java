package com.nike.riposte.server;

import com.nike.riposte.server.channelpipeline.HttpChannelInitializer;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.config.distributedtracing.DefaultRiposteDistributedTracingConfigImpl;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.hooks.PostServerStartupHook;
import com.nike.riposte.server.hooks.PreServerStartupHook;
import com.nike.riposte.server.hooks.ServerShutdownHook;
import com.nike.riposte.server.http.ResponseSender;
import com.nike.wingtips.Span;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * Netty Server implementation for Riposte that supports HTTP endpoints. Takes in a {@link ServerConfig} in the
 * constructor that provides all the configuration options. Call {@link #startup()} to kick off the server, bind to a
 * port, and start accepting requests.
 *
 * @author Nic Munroe
 */
public class Server {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final @NotNull ServerConfig serverConfig;

    private final List<EventLoopGroup> eventLoopGroups = new ArrayList<>();
    private final List<Channel> channels = new ArrayList<>();
    private boolean startedUp = false;
    private boolean hasShutdown = false;

    @SuppressWarnings("WeakerAccess")
    public static final String SERVER_BOSS_CHANNEL_DEBUG_LOGGER_NAME = "ServerBossChannelDebugLogger";

    public Server(@NotNull ServerConfig serverConfig) {
        this.serverConfig = serverConfig;
    }

    public void startup() throws CertificateException, IOException, InterruptedException {
        if (startedUp) {
            throw new IllegalArgumentException("This Server instance has already started. "
                                               + "You can only call startup() once");
        }

        // Figure out what port to bind to.
        int port = Integer.parseInt(
            System.getProperty("endpointsPort", serverConfig.isEndpointsUseSsl()
                                                ? String.valueOf(serverConfig.endpointsSslPort())
                                                : String.valueOf(serverConfig.endpointsPort())
            )
        );

        // Configure SSL if desired.
        final SslContext sslCtx;
        if (serverConfig.isEndpointsUseSsl()) {
            sslCtx = serverConfig.createSslContext();
        }
        else {
            sslCtx = null;
        }

        // Configure the server
        EventLoopGroup bossGroup;
        EventLoopGroup workerGroup;
        Class<? extends ServerChannel> channelClass;

        // Use the native epoll event loop groups if available for maximum performance
        //      (see http://netty.io/wiki/native-transports.html). If they're not available then fall back to standard
        //      NIO event loop group.
        if (Epoll.isAvailable()) {
            logger.info("The epoll native transport is available. Using epoll instead of NIO. "
                        + "riposte_server_using_native_epoll_transport=true");
            bossGroup = (serverConfig.bossThreadFactory() == null)
                        ? new EpollEventLoopGroup(serverConfig.numBossThreads())
                        : new EpollEventLoopGroup(serverConfig.numBossThreads(), serverConfig.bossThreadFactory());
            workerGroup = (serverConfig.workerThreadFactory() == null)
                          ? new EpollEventLoopGroup(serverConfig.numWorkerThreads())
                          : new EpollEventLoopGroup(serverConfig.numWorkerThreads(),
                                                    serverConfig.workerThreadFactory());
            channelClass = EpollServerSocketChannel.class;
        }
        else {
            logger.info("The epoll native transport is NOT available or you are not running on a compatible "
                        + "OS/architecture. Using NIO. riposte_server_using_native_epoll_transport=false");
            bossGroup = (serverConfig.bossThreadFactory() == null)
                        ? new NioEventLoopGroup(serverConfig.numBossThreads())
                        : new NioEventLoopGroup(serverConfig.numBossThreads(), serverConfig.bossThreadFactory());
            workerGroup = (serverConfig.workerThreadFactory() == null)
                          ? new NioEventLoopGroup(serverConfig.numWorkerThreads())
                          : new NioEventLoopGroup(serverConfig.numWorkerThreads(), serverConfig.workerThreadFactory());
            channelClass = NioServerSocketChannel.class;
        }

        eventLoopGroups.add(bossGroup);
        eventLoopGroups.add(workerGroup);

        // Figure out which channel initializer should set up the channel pipelines for new channels.
        ChannelInitializer<SocketChannel> channelInitializer = serverConfig.customChannelInitializer();
        if (channelInitializer == null) {

            DistributedTracingConfig<Span> wingtipsDistributedTracingConfig =
                getOrGenerateWingtipsDistributedTracingConfig(serverConfig);

            // No custom channel initializer, so use the default
            channelInitializer = new HttpChannelInitializer(
                sslCtx, serverConfig.maxRequestSizeInBytes(), serverConfig.appEndpoints(),
                serverConfig.requestAndResponseFilters(),
                serverConfig.longRunningTaskExecutor(), serverConfig.riposteErrorHandler(),
                serverConfig.riposteUnhandledErrorHandler(),
                serverConfig.requestContentValidationService(), serverConfig.defaultRequestContentDeserializer(),
                new ResponseSender(
                    serverConfig.defaultResponseContentSerializer(), serverConfig.errorResponseBodySerializer(),
                    wingtipsDistributedTracingConfig
                ),
                serverConfig.metricsListener(),
                serverConfig.defaultCompletableFutureTimeoutInMillisForNonblockingEndpoints(),
                serverConfig.accessLogger(), serverConfig.pipelineCreateHooks(),
                serverConfig.requestSecurityValidator(), serverConfig.workerChannelIdleTimeoutMillis(),
                serverConfig.proxyRouterConnectTimeoutMillis(), serverConfig.incompleteHttpCallTimeoutMillis(),
                serverConfig.maxOpenIncomingServerChannels(), serverConfig.isDebugChannelLifecycleLoggingEnabled(),
                serverConfig.userIdHeaderKeys(), serverConfig.responseCompressionThresholdBytes(),
                serverConfig.httpRequestDecoderConfig(), wingtipsDistributedTracingConfig
            );
        }

        // Create the server bootstrap
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(channelClass)
         .childHandler(channelInitializer);

        // execute pre startup hooks
        List<@NotNull PreServerStartupHook> preServerStartupHooks = serverConfig.preServerStartupHooks();
        if (preServerStartupHooks != null) {
            for (PreServerStartupHook hook : preServerStartupHooks) {
                hook.executePreServerStartupHook(b);
            }
        }

        if (serverConfig.isDebugChannelLifecycleLoggingEnabled())
            b.handler(new LoggingHandler(SERVER_BOSS_CHANNEL_DEBUG_LOGGER_NAME, LogLevel.DEBUG));

        // Bind the server to the desired port and start it up so it is ready to receive requests
        Channel ch = b.bind(port)
                      .sync()
                      .channel();

        // execute post startup hooks
        List<@NotNull PostServerStartupHook> postServerStartupHooks = serverConfig.postServerStartupHooks();
        if (postServerStartupHooks != null) {
            for (PostServerStartupHook hook : postServerStartupHooks) {
                hook.executePostServerStartupHook(serverConfig, ch);
            }
        }

        channels.add(ch);

        logger.info("Server channel open and accepting " + (serverConfig.isEndpointsUseSsl() ? "https" : "http")
                    + " requests on port " + port);
        startedUp = true;

        // Add a shutdown hook so we can gracefully stop the server when the JVM is going down
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                shutdown();
            }
            catch (Exception e) {
                logger.warn("Error shutting down Riposte", e);
                throw new RuntimeException(e);
            }
        }));
    }

    @SuppressWarnings("WeakerAccess")
    protected @NotNull DistributedTracingConfig<Span> getOrGenerateWingtipsDistributedTracingConfig(
        @NotNull ServerConfig serverConfig
    ) {
        DistributedTracingConfig<?> distributedTracingConfigRaw = serverConfig.distributedTracingConfig();
        if (distributedTracingConfigRaw == null) {
            distributedTracingConfigRaw = DefaultRiposteDistributedTracingConfigImpl.getDefaultInstance();
        }

        if (!Span.class.equals(distributedTracingConfigRaw.getSpanClassType())) {
            throw new IllegalArgumentException(
                "Your ServerConfig.distributedTracingConfig() does not support Wingtips Spans. Riposte currently "
                + "requires a DistributedTracingConfig that handles Wingtips Spans."
            );
        }

        //noinspection unchecked - we manually verified (above) that it handles Wingtips Spans.
        return (DistributedTracingConfig<Span>) distributedTracingConfigRaw;
    }

    public synchronized void shutdown() throws InterruptedException {
        if (hasShutdown) {
            return;
        }

        try {
            logger.info("Shutting down Riposte...");
            List<ChannelFuture> channelCloseFutures = new ArrayList<>();
            for (Channel ch : channels) {
                // execute shutdown hooks
                List<@NotNull ServerShutdownHook> serverShutdownHooks = serverConfig.serverShutdownHooks();
                if (serverShutdownHooks != null) {
                    for (ServerShutdownHook hook : serverShutdownHooks) {
                        hook.executeServerShutdownHook(serverConfig, ch);
                    }
                }

                channelCloseFutures.add(ch.close());
            }
            for (ChannelFuture chf : channelCloseFutures) {
                chf.sync();
            }
        }
        finally {
            hasShutdown = true;
            eventLoopGroups.forEach(EventExecutorGroup::shutdownGracefully);
            logger.info("...Riposte shutdown complete");
        }
    }
}
