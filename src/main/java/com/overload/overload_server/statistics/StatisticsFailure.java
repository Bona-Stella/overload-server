package com.overload.overload_server.statistics;

public final class StatisticsFailure extends RuntimeException {
    private final int status;
    public StatisticsFailure(int status, String code) { super(code); this.status = status; }
    public int status() { return status; }
}
