package mb.statix.constraints;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multiset;

import mb.nabl2.terms.ITerm;
import mb.nabl2.terms.ITermVar;
import mb.nabl2.terms.substitution.IRenaming;
import mb.nabl2.terms.substitution.ISubstitution;
import mb.nabl2.util.TermFormatter;
import mb.statix.constraints.messages.IMessage;
import mb.statix.solver.IConstraint;

public class CUser implements IConstraint, Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final List<ITerm> args;

    private final @Nullable IConstraint cause;
    private final @Nullable IMessage message;

    public CUser(String name, Iterable<? extends ITerm> args) {
        this(name, args, null, null);
    }

    public CUser(String name, Iterable<? extends ITerm> args, @Nullable IMessage message) {
        this(name, args, null, message);
    }

    public CUser(String name, Iterable<? extends ITerm> args, @Nullable IConstraint cause, @Nullable IMessage message) {
        this.name = name;
        this.args = ImmutableList.copyOf(args);
        this.cause = cause;
        this.message = message;
    }

    public String name() {
        return name;
    }

    public List<ITerm> args() {
        return args;
    }

    @Override public Optional<IConstraint> cause() {
        return Optional.ofNullable(cause);
    }

    @Override public CUser withCause(@Nullable IConstraint cause) {
        return new CUser(name, args, cause, message);
    }

    @Override public Optional<IMessage> message() {
        return Optional.ofNullable(message);
    }

    @Override public CUser withMessage(@Nullable IMessage message) {
        return new CUser(name, args, cause, message);
    }

    @Override public <R> R match(Cases<R> cases) {
        return cases.caseUser(this);
    }

    @Override public <R, E extends Throwable> R matchOrThrow(CheckedCases<R, E> cases) throws E {
        return cases.caseUser(this);
    }

    @Override public Multiset<ITermVar> getVars() {
        final ImmutableMultiset.Builder<ITermVar> vars = ImmutableMultiset.builder();
        for (ITerm a : args) {
            vars.addAll(a.getVars());
        }
        return vars.build();
    }

    @Override public CUser apply(ISubstitution.Immutable subst) {
        return new CUser(name, subst.apply(args), cause, message == null ? null : message.apply(subst));
    }

    @Override public CUser apply(IRenaming subst) {
        return new CUser(name, subst.apply(args), cause, message == null ? null : message.apply(subst));
    }

    @Override public String toString(TermFormatter termToString) {
        final StringBuilder sb = new StringBuilder();
        sb.append(name);
        sb.append("(");
        sb.append(termToString.format(args));
        sb.append(")");
        return sb.toString();
    }

    @Override public String toString() {
        return toString(ITerm::toString);
    }

    @Override
    public boolean equals(Object o) {
        if(this == o) return true;
        if(o == null || getClass() != o.getClass()) return false;
        CUser cUser = (CUser)o;
        return Objects.equals(name, cUser.name) &&
            Objects.equals(args, cUser.args) &&
            Objects.equals(cause, cUser.cause) &&
            Objects.equals(message, cUser.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, args, cause, message);
    }
}
