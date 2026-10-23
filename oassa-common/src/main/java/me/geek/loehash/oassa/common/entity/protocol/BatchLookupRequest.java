package me.geek.loehash.oassa.common.entity.protocol;

import java.util.List;

/**
 * 批量路由查询请求。
 *
 * @param paths 待查询的对象路径列表
 */
public record BatchLookupRequest(List<String> paths) {
}
