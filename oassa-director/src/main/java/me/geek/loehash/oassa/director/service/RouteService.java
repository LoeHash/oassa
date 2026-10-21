package me.geek.loehash.oassa.director.service;

import me.geek.loehash.oassa.common.entity.RouteEntry;
import me.geek.loehash.oassa.common.entity.protocol.RouteLookupResponse;

public interface RouteService {
    public RouteLookupResponse lookup(String path);
    public void register(RouteEntry entry);
    public boolean unregister(String mountPath);


}
