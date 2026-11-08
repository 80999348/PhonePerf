package com.example.phoneperf

import com.example.phoneperf.data.Metric
import com.example.phoneperf.data.toHumanBytes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 纯 JVM 单元测试，覆盖不依赖 Android 框架的纯逻辑。
 * 运行：./gradlew test
 */
class LogicTest {

    @Test
    fun humanBytes_gigabyte() {
        assertEquals("1.0 GB", (1024L * 1024 * 1024).toHumanBytes())
        assertEquals("512 MB", (512L * 1024 * 1024).toHumanBytes())
        assertEquals("0 B", 0L.toHumanBytes())
    }

    @Test
    fun metric_display_joins_unit() {
        assertEquals("42 %", Metric("x", "42", "%").display)
        assertEquals("1.82", Metric("x", "1.82").display)
    }

    @Test
    fun metric_fraction_is_optional() {
        assertEquals(null, Metric("x", "1").fraction)
        assertEquals(0.5f, Metric("x", "1", fraction = 0.5f).fraction)
    }
}
