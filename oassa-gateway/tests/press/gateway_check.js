import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

// ---------------- 用例定义 ----------------
const paths = [
  { path: '/a/b/c/file.txt', location: 'http://streamer-c.example.com/a/b/c/file.txt' },
  { path: '/a/b/file.txt',   location: 'http://streamer-a.example.com/a/b/file.txt' },
  { path: '/a/file.txt',     location: 'http://streamer-b.example.com/a/file.txt' },
  { path: '/a/bc/file.txt',  location: 'http://streamer-b.example.com/a/bc/file.txt' },
  { path: '/a/b/c/d/e.txt',  location: 'http://streamer-c.example.com/a/b/c/d/e.txt' },
];

// ---------------- 目标网关 ----------------
// 用环境变量覆盖：k6 run -e BASE_URL=http://1.2.3.4:8080 gateway_check.js
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';

// ---------------- 压测参数 ----------------
export const options = {
  scenarios: {
    gateway: {
      executor: 'constant-vus',   // 固定并发 VU
      vus: Number(__ENV.VUS || 500),
      duration: __ENV.DURATION || '60s',
    },
  },
  thresholds: {
    // 精确校验的错误率必须为 0（可按需放宽）
    'gateway_errors': ['rate==0'],
    // 302 状态码必须全部命中
    'checks': ['rate>0.999'],
  },
  // 关闭 k6 默认的 http 请求失败阈值干扰（我们自定义）
  noConnectionReuse: false,
};

// ---------------- 自定义指标 ----------------
const gatewayErrors = new Counter('gateway_errors');          // 错误总数
const gatewayErrorRate = new Rate('gateway_error_rate');      // 错误率
const statusMismatch = new Counter('status_mismatch');        // 状态码不对
const locationMismatch = new Counter('location_mismatch');    // Location 不对

// 错误日志限流
let printedErrors = 0;
const MAX_ERROR_PRINTS = 20;

// ---------------- 主流程 ----------------
export default function () {
  // 均匀随机取一条用例
  const item = paths[Math.floor(Math.random() * paths.length)];

  const res = http.get(`${BASE_URL}${item.path}`, {
    redirects: 0,                 // 关键：不跟随重定向，才能拿到 302
    headers: { 'Accept': '*/*' },
    tags: { name: item.path },    // 让 k6 按 path 分组统计
  });

  // Location header 大小写兼容
  const location = res.headers['Location'] || res.headers['location'];

  const okStatus = res.status === 302;
  const okLocation = location === item.location;

  if (!okStatus) statusMismatch.add(1);
  if (!okLocation) locationMismatch.add(1);

  const ok = okStatus && okLocation;

  if (!ok) {
    gatewayErrors.add(1);
    gatewayErrorRate.add(1);

    if (printedErrors < MAX_ERROR_PRINTS) {
      printedErrors++;
      console.error(
        `[ERROR] path=${item.path} status=${res.status}(expect 302) ` +
        `location=${location}(expect ${item.location})`
      );
    }
  } else {
    gatewayErrorRate.add(0);
  }

  check(res, {
    'status is 302': () => okStatus,
    'location matches': () => okLocation,
  });
}
