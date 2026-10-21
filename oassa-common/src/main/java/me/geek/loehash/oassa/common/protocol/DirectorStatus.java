package me.geek.loehash.oassa.common.protocol;

public final class DirectorStatus {

    private DirectorStatus() {}

    // 成功
    public static final int SUCCESS = 0;

    // 客户端请求错误：1 ~ 99
    public static final int INVALID_REQUEST = 1;
    public static final int INVALID_PATH = 2;
    public static final int METHOD_NOT_ALLOWED = 3;
    public static final int ENDPOINT_NOT_FOUND = 4;

    // 路由查询结果：100 ~ 199
    public static final int ROUTE_NOT_FOUND = 100;

    // 请求限制：200 ~ 299
    public static final int TOO_MANY_REQUESTS = 200;

    // 服务端错误：500 ~ 599
    public static final int INTERNAL_ERROR = 500;
    public static final int SERVICE_UNAVAILABLE = 501;
    public static final int ROUTE_SERVICE_ERROR = 502;
}