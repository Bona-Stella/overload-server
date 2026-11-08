package com.overload.overload_server.deload;

public final class DeloadPeriodFailure extends RuntimeException {
    final int status;final String code;
    DeloadPeriodFailure(int status,String code){super(code);this.status=status;this.code=code;}
    public int status(){return status;}
    public String code(){return code;}
    static DeloadPeriodFailure invalid(){return new DeloadPeriodFailure(400,"INVALID_REQUEST");}
    static DeloadPeriodFailure conflict(String code){return new DeloadPeriodFailure(409,code);}
}
