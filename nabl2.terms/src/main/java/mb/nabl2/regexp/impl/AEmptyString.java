package mb.nabl2.regexp.impl;

import org.immutables.serial.Serial;
import org.immutables.value.Value;

import mb.nabl2.regexp.IRegExp;

@Value.Immutable
@Serial.Version(value = 42L)
abstract class AEmptyString<S> implements IRegExp<S> {

    @Override public <T> T match(IRegExp.ICases<S, T> visitor) {
        return visitor.emptyString();
    }

    @Override public String toString() {
        return "e";
    }

}
