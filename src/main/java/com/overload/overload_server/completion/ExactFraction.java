package com.overload.overload_server.completion;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Exact arithmetic. decimal/percent are display projections and must never be calculation inputs. */
public record ExactFraction(BigInteger numerator, BigInteger denominator) implements Comparable<ExactFraction> {
    public static final ExactFraction ZERO=of(0,1);
    public ExactFraction {
        if(numerator==null||denominator==null||denominator.signum()<=0)throw new IllegalArgumentException("Positive denominator required");
        var gcd=numerator.gcd(denominator);
        numerator=numerator.divide(gcd);denominator=denominator.divide(gcd);
    }
    public static ExactFraction of(int numerator,int denominator) {
        return new ExactFraction(BigInteger.valueOf(numerator),BigInteger.valueOf(denominator));
    }
    public ExactFraction add(ExactFraction other) {
        return new ExactFraction(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),denominator.multiply(other.denominator));
    }
    @Override public int compareTo(ExactFraction other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }
    public String decimal() {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator),12,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
    public String percent(int days) {
        if(days<=0)throw new IllegalArgumentException("Positive planned days required");
        return new BigDecimal(numerator).multiply(BigDecimal.valueOf(100))
            .divide(new BigDecimal(denominator).multiply(BigDecimal.valueOf(days)),6,RoundingMode.HALF_UP)
            .min(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
    }
}
