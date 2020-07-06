package mb.nabl2.regexp.impl;

import org.immutables.serial.Serial;
import org.immutables.value.Value;

import mb.nabl2.regexp.IRegExp;

@Value.Immutable
@Serial.Version(value = 42L)
abstract class AAnd<S> implements IRegExp<S> {

    @Value.Parameter public abstract IRegExp<S> getLeft();

    @Value.Parameter public abstract IRegExp<S> getRight();

    @Override public <T> T match(IRegExp.ICases<S, T> visitor) {
        return visitor.and(getLeft(), getRight());
    }

    @Override public String toString() {
        return "(" + getLeft() + " & " + getRight() + ")";
    }

}