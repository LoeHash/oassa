package me.geek.loehash.oassa.common.entity.protocol;


import me.geek.loehash.oassa.common.protocol.DirectorStatus;


/***
 * 成功
 * {
 *   "code": 0,
 *   "message": "OK",
 *   "data": {
 *     "streamer": "http://streamer-c1.example.com",
 *     "mountPath": "/a/b/c/",
 *     "ttlSeconds": 30
 *   }
 * }
 * @param code
 * @param message
 * @param data
 * @param <T>
 */
public record DirectorResponse<T>(
        int code,
        String message,
        T data
) {

    public static <T> DirectorResponse<T> success(T data) {
        return new DirectorResponse<>(
                DirectorStatus.SUCCESS,
                "OK",
                data
        );
    }

    public static <T> DirectorResponse<T> error(int code, String message) {
        return new DirectorResponse<>(
                code,
                message,
                null
        );
    }
}