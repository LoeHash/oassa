package me.geek.loehash.oassa.director.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import me.geek.loehash.oassa.common.controller.ApiController;
import me.geek.loehash.oassa.common.entity.protocol.BatchLookupRequest;
import me.geek.loehash.oassa.common.entity.protocol.DirectorResponse;
import me.geek.loehash.oassa.common.entity.protocol.RouteLookupResponse;
import me.geek.loehash.oassa.common.protocol.DirectorStatus;
import me.geek.loehash.oassa.director.service.RouteService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 路由查询控制器。
 * <p>
 * 负责路径前缀 {@code /internal/v1/routes} 下的所有接口：
 * <ul>
 *   <li>{@code GET  /lookup?path=xxx}       — 单条路由查询</li>
 *   <li>{@code POST /batch-lookup}          — 批量路由查询（body: {"paths":[...]}）</li>
 * </ul>
 */
public class RouteController extends ApiController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @Override
    public String basePath() {
        return "/internal/v1/routes";
    }

    @Override
    public void handle(ChannelHandlerContext ctx, FullHttpRequest req, String subPath) {
        switch (subPath) {
            case "/lookup":
                handleLookup(ctx, req);
                break;
            case "/batch-lookup":
                handleBatchLookup(ctx, req);
                break;
            default:
                sendError(ctx, req, HttpResponseStatus.NOT_FOUND,
                        DirectorStatus.ENDPOINT_NOT_FOUND, "Endpoint not found: " + subPath);
        }
    }

    /**
     * GET /internal/v1/routes/lookup?path=xxx
     */
    private void handleLookup(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!HttpMethod.GET.equals(req.method())) {
            sendError(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    DirectorStatus.METHOD_NOT_ALLOWED, "Only GET is supported");
            return;
        }

        QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
        Map<String, List<String>> params = decoder.parameters();
        List<String> values = params.get("path");

        if (values == null || values.size() != 1) {
            sendError(ctx, req, HttpResponseStatus.BAD_REQUEST,
                    DirectorStatus.INVALID_PATH, "Exactly one path parameter is required");
            return;
        }

        String path = values.get(0);

        if (!isValidObjectPath(path)) {
            sendError(ctx, req, HttpResponseStatus.BAD_REQUEST,
                    DirectorStatus.INVALID_PATH, "Invalid object path");
            return;
        }

        try {
            RouteLookupResponse route = routeService.lookup(path);

            if (route == null) {
                sendError(ctx, req, HttpResponseStatus.NOT_FOUND,
                        DirectorStatus.ROUTE_NOT_FOUND, "No matching route");
                return;
            }

            sendJson(ctx, req, HttpResponseStatus.OK, DirectorResponse.success(route));
        } catch (Exception e) {
            log.error("Route lookup failed, path={}", path, e);
            sendError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    DirectorStatus.ROUTE_SERVICE_ERROR, "Route lookup failed");
        }
    }

    /**
     * POST /internal/v1/routes/batch-lookup
     * body: {"paths": ["/a/b/c/", "/x/y/"]}
     */
    private void handleBatchLookup(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!HttpMethod.POST.equals(req.method())) {
            sendError(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    DirectorStatus.METHOD_NOT_ALLOWED, "Only POST is supported");
            return;
        }

        BatchLookupRequest request;
        try {
            ByteBuf content = req.content();
            String body = content.toString(StandardCharsets.UTF_8);
            request = MAPPER.readValue(body, BatchLookupRequest.class);
        } catch (Exception e) {
            sendError(ctx, req, HttpResponseStatus.BAD_REQUEST,
                    DirectorStatus.INVALID_REQUEST, "Invalid request body");
            return;
        }

        if (request == null || request.paths() == null || request.paths().isEmpty()) {
            sendError(ctx, req, HttpResponseStatus.BAD_REQUEST,
                    DirectorStatus.INVALID_REQUEST, "paths must not be empty");
            return;
        }

        try {
            Map<String, RouteLookupResponse> result = routeService.batchLookup(request.paths());
            sendJson(ctx, req, HttpResponseStatus.OK, DirectorResponse.success(result));
        } catch (Exception e) {
            log.error("Batch route lookup failed", e);
            sendError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    DirectorStatus.ROUTE_SERVICE_ERROR, "Batch route lookup failed");
        }
    }

    private boolean isValidObjectPath(String path) {
        return path != null
                && path.startsWith("/")
                && !path.startsWith("//")
                && !path.contains("\\")
                && !path.contains("?")
                && !path.contains("#")
                && !path.contains("\0");
    }
}
