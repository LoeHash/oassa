local paths = {
    {
        path = "/a/b/c/file.txt",
        location = "http://streamer-c.example.com/a/b/c/file.txt"
    },
    {
        path = "/a/b/file.txt",
        location = "http://streamer-a.example.com/a/b/file.txt"
    },
    {
        path = "/a/file.txt",
        location = "http://streamer-b.example.com/a/file.txt"
    },
    {
        path = "/a/bc/file.txt",
        location = "http://streamer-b.example.com/a/bc/file.txt"
    },
    {
        path = "/a/b/c/d/e.txt",
        location = "http://streamer-c.example.com/a/b/c/d/e.txt"
    }
}

local index = 0
local error_count = 0
local checked_count = 0
local printed_errors = 0

function request()
    index = index + 1

    local item = paths[((index - 1) % #paths) + 1]

    return wrk.format(
        "GET",
        item.path,
        {
            ["Accept"] = "*/*"
        }
    )
end

function response(status, headers, body)
    checked_count = checked_count + 1

    local expected_index = ((checked_count - 1) % #paths) + 1
    local expected = paths[expected_index]

    local location = headers["Location"] or headers["location"]

    if status ~= 302 or location ~= expected.location then
        error_count = error_count + 1

        -- 限制错误输出，避免压测时大量打印影响结果。
        if printed_errors < 10 then
            printed_errors = printed_errors + 1

            print(string.format(
                "[ERROR] status=%d expected_status=302 location=%s expected_location=%s",
                status,
                tostring(location),
                expected.location
            ))
        end
    end
end

function done(summary, latency, requests)
    print("")
    print("========== Gateway correctness ==========")
    print("Responses checked: " .. checked_count)
    print("Incorrect responses: " .. error_count)

    if error_count > 0 then
        print("RESULT: FAILED")
    else
        print("RESULT: PASSED")
    end

    print("==========================================")
end