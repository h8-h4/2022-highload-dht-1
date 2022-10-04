package ok.dht.test.shestakova;

import jdk.incubator.foreign.MemorySegment;
import ok.dht.Service;
import ok.dht.ServiceConfig;
import ok.dht.test.ServiceFactory;
import ok.dht.test.shestakova.dao.MemorySegmentDao;
import ok.dht.test.shestakova.dao.base.BaseEntry;
import ok.dht.test.shestakova.dao.base.Config;
import one.nio.http.*;
import one.nio.server.AcceptorConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class DemoService implements Service {

    private final ServiceConfig config;
    private HttpServer server;
    private MemorySegmentDao dao;
    private final long FLUSH_THRESHOLD = 1 << 20; // 1 MB
    private final int POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private final int QUEUE_CAPACITY = 256;
    private ExecutorService workersPool;

    public DemoService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<?> start() throws IOException {
        dao = new MemorySegmentDao(new Config(config.workingDir(), FLUSH_THRESHOLD));
        workersPool = new ThreadPoolExecutor(
                POOL_SIZE,
                POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY)
        );
        server = new HttpServer(createConfigFromPort(config.selfPort())) {
            @Override
            public void handleRequest(Request request, HttpSession session) {
                workersPool.execute(() -> {
                    try {
                        super.handleRequest(request, session);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            @Override
            public void handleDefault(Request request, HttpSession session) throws IOException {
                Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
                session.sendResponse(response);
            }
        };
        server.addRequestHandlers(this);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> stop() throws IOException {
        server.stop();
        workersPool.shutdown();
        try {
            if (!workersPool.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("Error during termination");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response handleGet(@Param(value = "id") String id) {
        if (id == null || id.isEmpty()) {
            return new Response(
                    Response.BAD_REQUEST,
                    Response.EMPTY
            );
        }

        BaseEntry<MemorySegment> entry = dao.get(fromString(id));

        if (entry == null) {
            return new Response(
                    Response.NOT_FOUND,
                    Response.EMPTY
            );
        }

        return new Response(
                Response.OK,
                entry.value().toByteArray()
        );
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response handlePut(Request request, @Param(value = "id") String id) {
        if (id == null || id.isEmpty()) {
            return new Response(
                    Response.BAD_REQUEST,
                    Response.EMPTY
            );
        }

        dao.upsert(new BaseEntry<>(
                fromString(id),
                MemorySegment.ofArray(request.getBody())
        ));

        return new Response(
                Response.CREATED,
                Response.EMPTY
        );
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response handleDelete(@Param(value = "id") String id) {
        if (id == null || id.isEmpty()) {
            return new Response(
                    Response.BAD_REQUEST,
                    Response.EMPTY
            );
        }

        dao.upsert(new BaseEntry<>(
                fromString(id),
                null
        ));

        return new Response(
                Response.ACCEPTED,
                Response.EMPTY
        );
    }

    private static HttpServerConfig createConfigFromPort(int port) {
        HttpServerConfig httpConfig = new HttpServerConfig();
        AcceptorConfig acceptor = new AcceptorConfig();
        acceptor.port = port;
        acceptor.reusePort = true;
        httpConfig.acceptors = new AcceptorConfig[]{acceptor};
        return httpConfig;
    }

    public MemorySegment fromString(String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    @ServiceFactory(stage = 1, week = 1)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new DemoService(config);
        }
    }
}
