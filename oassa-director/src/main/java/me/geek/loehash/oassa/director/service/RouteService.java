package me.geek.loehash.oassa.director.service;

import me.geek.loehash.oassa.common.entity.RouteEntry;
import me.geek.loehash.oassa.common.entity.protocol.RouteLookupResponse;

import java.util.List;
import java.util.Map;

public interface RouteService {

    /**
     * 查询单个路径的路由（带本地缓存）。
     */
    RouteLookupResponse lookup(String path);

    /**
     * 批量查询多个路径的路由（带本地缓存）。
     * 返回的 map 中，未找到路由的路径不会出现在结果里。
     */
    Map<String, RouteLookupResponse> batchLookup(List<String> paths);

    void register(RouteEntry entry);

    boolean unregister(String mountPath);
}
