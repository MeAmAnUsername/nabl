package mb.nabl2.terms.build;

import java.util.Objects;

import org.immutables.serial.Serial;
import org.immutables.value.Value;
import org.metaborg.util.collection.CapsuleUtil;
import org.metaborg.util.functions.Action1;

import io.usethesource.capsule.Set;
import mb.nabl2.terms.IStringTerm;
import mb.nabl2.terms.ITermVar;
import mb.nabl2.terms.Terms;

@Value.Immutable
@Serial.Version(value = 42L)
abstract class AStringTerm extends AbstractTerm implements IStringTerm {

    @Value.Parameter @Override public abstract String getValue();

    @Override public boolean isGround() {
        return true;
    }

    @Override public Set.Immutable<ITermVar> getVars() {
        return CapsuleUtil.immutableSet();
    }

    @Override public void visitVars(@SuppressWarnings("unused") Action1<ITermVar> onVar) {
    }

    @Override public <T> T match(Cases<T> cases) {
        return cases.caseString(this);
    }

    @Override public <T, E extends Throwable> T matchOrThrow(CheckedCases<T, E> cases) throws E {
        return cases.caseString(this);
    }


    @Override public int hashCode() {
        return Objects.hash(getValue());
    }

    @Override public boolean equals(Object other) {
        if(this == other)
            return true;
        if(!(other instanceof IStringTerm))
            return false;
        IStringTerm that = (IStringTerm) other;
        if(this.hashCode() != that.hashCode())
            return false;
        // @formatter:off
        return Objects.equals(this.getValue(), that.getValue());
        // @formatter:on
    }


    @Override public String toString() {
        return "\"" + Terms.escapeString(getValue()) + "\"";
    }

}
