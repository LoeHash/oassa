package me.geek.loehash.oassa.gateway.obj;

import me.geek.loehash.oassa.common.entity.protocol.RouteLookupResponse;
import me.geek.loehash.oassa.common.protocol.DirectorClient;
import me.geek.loehash.oassa.gateway.cache.PathMountCache;

public class AllObjects {
    public static DirectorClient directorClient;
    public static PathMountCache<RouteLookupResponse> pmc;

}
