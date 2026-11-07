package com.overload.overload_server.deload;

public final class DeloadAnalysisFailure extends RuntimeException {
    enum Reason {
        INVALID_REQUEST(400), AUTHENTICATION_REQUIRED(401), DATA_GENERATION_MISMATCH(409),
        DELOAD_VERSION_CONFLICT(409), DELOAD_TIME_ZONE_CONFLICT(409), WRITE_CONFLICT(409),
        DELOAD_SETTINGS_MISSING(500);
        final int status;
        Reason(int status) { this.status=status; }
    }
    final Reason reason;
    DeloadAnalysisFailure(Reason reason) { super(reason.name()); this.reason=reason; }
    public int status() { return reason.status; }
    public String code() { return reason.name(); }
}
