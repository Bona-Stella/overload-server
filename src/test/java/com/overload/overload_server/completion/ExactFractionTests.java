package com.overload.overload_server.completion;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.assertj.core.api.Assertions.*;

class ExactFractionTests {
    @Test void fractionsAreReducedAndAdditionUsesExactIntegers() {
        var third=ExactFraction.of(2,6);
        assertThat(third.numerator()).isEqualTo(BigInteger.ONE);assertThat(third.denominator()).isEqualTo(BigInteger.valueOf(3));
        assertThat(third.add(ExactFraction.of(2,3))).isEqualTo(ExactFraction.of(1,1));
        assertThat(third.decimal()).isEqualTo("0.333333333333");assertThat(third.percent(1)).isEqualTo("33.333333");
        assertThat(ExactFraction.of(9,4).percent(4)).isEqualTo("56.25");
    }
    @Test void displayRoundingNeverChangesThresholdComparisons() {
        var value=new ExactFraction(new BigInteger("10000000000001"),new BigInteger("10000000000000"));
        assertThat(value.decimal()).isEqualTo("1");assertThat(value.compareTo(ExactFraction.of(1,1))).isPositive();
        assertThat(value.add(ExactFraction.ZERO)).isEqualTo(value);
        var huge=new ExactFraction(BigInteger.TEN.pow(100).add(BigInteger.ONE),BigInteger.TEN.pow(100));
        assertThat(huge.compareTo(ExactFraction.of(1,1))).isPositive();
    }
    @Test void invalidDenominatorsCannotCreateZeroOverZeroSuccess() {
        assertThatThrownBy(()->ExactFraction.of(0,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ExactFraction.of(1,-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ExactFraction.of(1,1).percent(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ExactFraction.of(0,100)).isEqualTo(ExactFraction.ZERO);
    }
}
