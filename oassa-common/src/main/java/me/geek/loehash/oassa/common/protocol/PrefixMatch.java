package me.geek.loehash.oassa.common.protocol;

// 对象路径以 / 开头。
// 注册路径必须以 / 结尾，根路径 / 除外。
// 匹配必须遵循路径边界，不能只做字符串前缀判断。
// 多条规则匹配时，选择最长的匹配路径。
// Gateway 只能缓存 Director 实际返回的规则，不自行推断更短路径的路由。
//  | 注册路径 | 请求路径 | 是否匹配 |
//  |---|---|---|
//  | `/a/` | `/a/b.png` | 是 |
//  | `/a/b/` | `/a/b/c.png` | 是 |
//  | `/a/b/` | `/a/bc.png` | 否 |
//  | `/a/b/` | `/a/` | 否 |
//  | `/` | `/a/b.png` | 是 |
public class PrefixMatch {
}
