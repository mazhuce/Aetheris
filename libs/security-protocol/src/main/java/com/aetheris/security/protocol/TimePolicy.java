package com.aetheris.security.protocol;

/** Lifetimes include no expiry grace; future skew only applies to issuedAt. */
public record TimePolicy(long userMaxTtlSeconds, long workloadMaxTtlSeconds, long futureSkewSeconds) {
    public TimePolicy {
        if(userMaxTtlSeconds<1 || userMaxTtlSeconds>300 || workloadMaxTtlSeconds<1
                || workloadMaxTtlSeconds>60 || futureSkewSeconds<0 || futureSkewSeconds>10)
            throw new ProtocolException("Invalid time policy");
    }
    public static TimePolicy defaults(){return new TimePolicy(120,30,5);}
}
