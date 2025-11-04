package com.example.app.util;

public class MathUtil {
    private MathUtil() {
    }

    public static int sum(int a, int b) {
        return a + b;
    }

    public static double average(int total, int count) {
        if (count == 0) {
            return 0;
        }
        return total / (double) count;
    }

    public static int max(int first, int second) {
        return Math.max(first, second);
    }

    public static int factorial(int value) {
        if (value <= 1) {
            return 1;
        }
        return value * factorial(value - 1);
    }
}
