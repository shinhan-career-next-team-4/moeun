package com.moeun.backend.chat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트용 ai-server. POST /chat 요청 본문을 기록하고 정해 둔 방식으로 답한다.
 * 기본은 "잡아 두기": 요청을 받은 뒤 {@link #release}가 불릴 때까지 답하지 않는다(PENDING 상태를 관찰하기 위해).
 */
final class AiServerStub {

    static final String ANSWER_OK = """
            {"messageType":"ANSWER","content":"스텁 답변","baselineScenario":"AVG",
             "calcRunId":null,"conditionChanges":null,"citationChunkIds":[]}""";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile HttpServer server;
    private volatile int lastPort;
    private volatile Reply mode = Reply.hold();
    private volatile CompletableFuture<Reply> held = new CompletableFuture<>();

    private AiServerStub() {
    }

    static AiServerStub start() {
        AiServerStub stub = new AiServerStub();
        stub.bind(0);
        return stub;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + lastPort;
    }

    /** 이후 요청에 바로 이 상태와 본문으로 답한다. */
    void respond(int status, String body) {
        mode = new Reply(status, body, Duration.ZERO, false);
    }

    /** 이후 요청에 delay만큼 기다린 뒤 답한다. */
    void respondAfter(Duration delay, int status, String body) {
        mode = new Reply(status, body, delay, false);
    }

    /** 이후 요청을 {@link #release}까지 잡아 둔다. */
    void hold() {
        held = new CompletableFuture<>();
        mode = Reply.hold();
    }

    /** 잡아 둔 요청(과 이후 잡힐 요청)에 이 상태와 본문으로 답한다. */
    void release(int status, String body) {
        held.complete(new Reply(status, body, Duration.ZERO, false));
    }

    /** 처음 상태로 돌린다. 잡혀 있던 요청은 500으로 끝낸다. */
    void reset() {
        held.complete(new Reply(500, "{\"error\":\"STUB_RESET\"}", Duration.ZERO, false));
        awaitIdle();
        requests.clear();
        hold();
    }

    void awaitIdle() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            sleep(20);
        }
    }

    List<JsonNode> requests() {
        return List.copyOf(requests);
    }

    /** 서버를 내려 연결이 거부되게 한다. */
    void goDown() {
        held.complete(new Reply(500, "{\"error\":\"STUB_DOWN\"}", Duration.ZERO, false));
        server.stop(0);
    }

    /** 같은 포트로 다시 띄운다. */
    void comeUp() {
        int boundPort = lastPort;
        for (int attempt = 0; ; attempt++) {
            try {
                bind(boundPort);
                return;
            } catch (IllegalStateException e) {
                if (attempt >= 50) {
                    throw e;
                }
                sleep(100);
            }
        }
    }

    private void bind(int bindPort) {
        try {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), bindPort);
            HttpServer created = HttpServer.create(address, 50);
            created.setExecutor(Executors.newCachedThreadPool());
            created.createContext("/chat", this::handle);
            created.start();
            server = created;
            lastPort = created.getAddress().getPort();
        } catch (BindException e) {
            throw new IllegalStateException("스텁 포트를 다시 열지 못했어요: " + bindPort, e);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        inFlight.incrementAndGet();
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(JSON.readTree(body));
            Reply reply = mode;
            if (reply.held()) {
                reply = held.get(60, TimeUnit.SECONDS);
            }
            if (!reply.delay().isZero()) {
                sleep(reply.delay().toMillis());
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception e) {
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
            inFlight.decrementAndGet();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Reply(int status, String body, Duration delay, boolean held) {

        static Reply hold() {
            return new Reply(0, "", Duration.ZERO, true);
        }
    }
}
