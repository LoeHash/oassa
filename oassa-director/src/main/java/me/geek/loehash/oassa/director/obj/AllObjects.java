package me.geek.loehash.oassa.director.obj;

import me.geek.loehash.oassa.common.controller.ControllerDispatcher;
import me.geek.loehash.oassa.director.controller.RouteController;
import me.geek.loehash.oassa.director.service.RouteService;
import me.geek.loehash.oassa.director.service.impl.RouteServiceImpl;

/**
 * 全局单例对象的统一注册点。
 * <p>
 * 在此统一 new 所有 service / controller / dispatcher，
 * 便于依赖管理与后期扩展。
 */
public class AllObjects {

    // ===== Service =====
    public static final RouteService routeService = new RouteServiceImpl();

    // ===== Controller =====
    public static final RouteController routeController = new RouteController(routeService);

    // ===== Dispatcher（统一注册所有 Controller）=====
    public static final ControllerDispatcher controllerDispatcher = buildDispatcher();

    private static ControllerDispatcher buildDispatcher() {
        ControllerDispatcher dispatcher = new ControllerDispatcher();
        dispatcher.register(routeController);
        return dispatcher;
    }
}
