# 基础运行（默认 500 VU / 60s）
k6 run -e BASE_URL=http://127.0.0.1:8081 gateway_check.js

# 调并发和时长
k6 run -e BASE_URL=http://127.0.0.1:8081 -e VUS=1000 -e DURATION=600s gateway_check.js

# 输出 JSON 报告
k6 run -e BASE_URL=http://127.0.0.1:8081 --out json=result.json gateway_check.js
