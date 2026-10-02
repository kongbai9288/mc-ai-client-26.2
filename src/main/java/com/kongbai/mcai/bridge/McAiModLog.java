package com.kongbai.mcai.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一日志出口。
 *
 * <p>fabric-loader 自带 slf4j，不需要 Fabric API，也不需要额外依赖。
 */
public final class McAiModLog {

    private static final Logger LOG = LoggerFactory.getLogger("mcai");

    private McAiModLog() {}

    public static Logger raw() {
        return LOG;
    }

    public static void info(String s) {
        LOG.info("[MCAI] {}", s);
    }

    public static void warn(String s) {
        LOG.warn("[MCAI] {}", s);
    }

    public static void error(String s, Throwable t) {
        LOG.error("[MCAI] {}", s, t);
    }

    public static void debug(String s) {
        LOG.debug("[MCAI] {}", s);
    }
}
