#wrk -t4 -c200 -d60s --latency http://localhost:8081/ > report/redirect_test.rep

wrk -t2 -c20 -d30s --latency \
    -s gateway-stress.lua \
    http://127.0.0.1:8081

wrk -t4 -c100 -d60s --latency \
    -s gateway-stress.lua \
    http://127.0.0.1:8081

wrk -t4 -c500 -d60s --latency \
    -s gateway-stress.lua \
    http://127.0.0.1:8081

wrk -t8 -c1000 -d60s --latency \
    -s gateway-stress.lua \
    http://127.0.0.1:8081