package me.geek.loehash.oassa.common.controller;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 控制器分发器。
 * <p>
 * 启动时统一注册所有 {@link ApiController}，运行时根据请求路径的最长前缀匹配
 * 分发到对应的 Controller。新增接口只需编写 Controller 子类并在此注册。
 */
public class ControllerDispatcher {

    private final Map<String, ApiController> controllers = new ConcurrentHashMap<>();

    /**
     * 注册一个控制器。
     */
    public void register(ApiController controller) {
        controllers.put(controller.basePath(), controller);
    }

    /**
     * 根据请求路径分发到对应控制器。
     *
     * @return true 表示已由某个控制器处理；false 表示未匹配到任何控制器
     */
    public boolean dispatch(ChannelHandlerContext ctx, FullHttpRequest req) {
        String path = new QueryStringDecoder(req.uri()).path();

        // 最长前缀匹配：找到 basePath 最长且为 path 前缀的控制器
        ApiController matched = null;
        int matchedLen = 0;

        for (Map.Entry<String, ApiController> entry : controllers.entrySet()) {
            String base = entry.getKey();
            if (path.startsWith(base) && base.length() > matchedLen) {
                matched = entry.getValue();
                matchedLen = base.length();
            }
        }

        if (matched == null) {
            return false;
        }

        String subPath = path.substring(matchedLen);
        matched.handle(ctx, req, subPath);
        return true;
    }
}
