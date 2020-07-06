package mb.nabl2.terms.matching;

import static mb.nabl2.terms.build.TermBuild.B;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.metaborg.util.functions.Action2;
import org.metaborg.util.functions.Function0;
import org.metaborg.util.functions.Function1;

import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import mb.nabl2.terms.ITerm;
import mb.nabl2.terms.ITermVar;
import mb.nabl2.terms.Terms;
import mb.nabl2.terms.substitution.IRenaming;
import mb.nabl2.terms.substitution.ISubstitution.Transient;
import mb.nabl2.terms.unification.u.IUnifier;

class ApplPattern extends Pattern {
    private static final long serialVersionUID = 1L;

    private final String op;
    private final List<Pattern> args;

    public ApplPattern(String op, Iterable<? extends Pattern> args, ImmutableClassToInstanceMap<Object> attachments) {
        super(attachments);
        this.op = op;
        this.args = ImmutableList.copyOf(args);
    }

    public String getOp() {
        return op;
    }

    public List<Pattern> getArgs() {
        return args;
    }

    @Override public Set<ITermVar> getVars() {
        ImmutableSet.Builder<ITermVar> vars = ImmutableSet.builder();
        for(Pattern arg : args) {
            vars.addAll(arg.getVars());
        }
        return vars.build();
    }

    @Override protected boolean matchTerm(ITerm term, Transient subst, IUnifier.Immutable unifier, Eqs eqs) {
        // @formatter:off
        return unifier.findTerm(term).match(Terms.<Boolean>cases()
            .appl(applTerm -> {
                if(applTerm.getArity() == this.args.size() && applTerm.getOp().equals(op)) {
                    return matchTerms(args, applTerm.getArgs(), subst, unifier, eqs);
                } else {
                    return false;
                }
            }).var(v -> {
                eqs.add(v, this);
                return true;
            }).otherwise(t -> {
                return false;
            })
        );
        // @formatter:on
    }

    @Override public Pattern apply(IRenaming subst) {
        return new ApplPattern(op, args.stream().map(p -> p.apply(subst)).collect(ImmutableList.toImmutableList()),
                getAttachments());
    }

    @Override public ApplPattern eliminateWld(Function0<ITermVar> fresh) {
        return new ApplPattern(op,
                args.stream().map(p -> p.eliminateWld(fresh)).collect(ImmutableList.toImmutableList()),
                getAttachments());
    }

    @Override protected ITerm asTerm(Action2<ITermVar, ITerm> equalities,
            Function1<Optional<ITermVar>, ITermVar> fresh) {
        return B.newAppl(op,
                args.stream().map(a -> a.asTerm(equalities, fresh)).collect(ImmutableList.toImmutableList()),
                getAttachments());
    }

    @Override public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append(op).append("(").append(args).append(")");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if(this == o) return true;
        if(o == null || getClass() != o.getClass()) return false;
        ApplPattern that = (ApplPattern)o;
        return Objects.equals(op, that.op) &&
            Objects.equals(args, that.args);
    }

    @Override
    public int hashCode() {
        return Objects.hash(op, args);
    }
}
