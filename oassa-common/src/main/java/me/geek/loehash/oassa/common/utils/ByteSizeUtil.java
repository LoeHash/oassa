package me.geek.loehash.oassa.common.utils;


/**
 * 字节大小计算工具类
 */
public class ByteSizeUtil {

    /** 单位：字节 */
    public static final int B = 0;
    /** 单位：KB */
    public static final int KB = 1;
    /** 单位：MB */
    public static final int MB = 2;
    /** 单位：GB */
    public static final int GB = 3;

    private static final long BASE = 1024L;
    private static final long INT_MAX = Integer.MAX_VALUE;

    private ByteSizeUtil() {
    }

    /**
     * 根据数值和单位计算字节数（int）
     *
     * @param value 数值
     * @param unit  单位（B / KB / MB / GB）
     * @return 字节数
     */
    public static int toBytes(int value, int unit) {
        long result = switch (unit) {
            case B -> value;
            case KB -> value * BASE;
            case MB -> value * BASE * BASE;
            case GB -> value * BASE * BASE * BASE;
            default -> throw new IllegalArgumentException("不支持的单位: " + unit);
        };
        return checkRange(result);
    }

    /**
     * 支持小数（int 返回）
     */
    public static int toBytes(double value, int unit) {
        long result = switch (unit) {
            case B -> (long) value;
            case KB -> (long) (value * BASE);
            case MB -> (long) (value * BASE * BASE);
            case GB -> (long) (value * BASE * BASE * BASE);
            default -> throw new IllegalArgumentException("不支持的单位: " + unit);
        };
        return checkRange(result);
    }

    /**
     * 校验是否超出 int 范围
     */
    private static int checkRange(long value) {
        if (value > INT_MAX || value < Integer.MIN_VALUE) {
            throw new ArithmeticException("字节数超出 int 范围: " + value);
        }
        return (int) value;
    }
}