package mb.statix.spec;

import static mb.nabl2.terms.build.TermBuild.B;
import static mb.nabl2.terms.matching.TermPattern.P;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.annotation.Nullable;

import org.immutables.serial.Serial;
import org.immutables.value.Value;
import org.metaborg.util.collection.CapsuleUtil;
import org.metaborg.util.functions.Action1;

import com.google.common.collect.ImmutableList;

import io.usethesource.capsule.Set;
import io.usethesource.capsule.util.stream.CapsuleCollectors;
import mb.nabl2.terms.ITerm;
import mb.nabl2.terms.ITermVar;
import mb.nabl2.terms.matching.Pattern;
import mb.nabl2.terms.substitution.FreshVars;
import mb.nabl2.terms.substitution.IRenaming;
import mb.nabl2.terms.substitution.ISubstitution;
import mb.nabl2.terms.unification.ud.PersistentUniDisunifier;
import mb.nabl2.util.TermFormatter;
import mb.statix.constraints.Constraints;
import mb.statix.solver.Delay;
import mb.statix.solver.IConstraint;
import mb.statix.solver.completeness.ICompleteness;
import mb.statix.spec.ApplyMode.Safety;

@Value.Immutable
@Serial.Version(42L)
public abstract class ARule {

    @Value.Default public String label() {
        return "";
    }

    @Value.Parameter public abstract String name();

    @Value.Parameter public abstract List<Pattern> params();

    @Value.Lazy public Set.Immutable<ITermVar> paramVars() {
        return params().stream().flatMap(t -> t.getVars().stream()).collect(CapsuleCollectors.toSet());
    }

    @Value.Parameter public abstract IConstraint body();

    @Value.Default public @Nullable ICompleteness.Immutable bodyCriticalEdges() {
        return null;
    }

    /**
     * Determines whether this rule is always true or false.
     *
     * @return {@code true} when this rule is always true;
     * {@code false} when the rule is always false;
     * otherwise, none
     */
    @Value.Lazy public Optional<Boolean> isAlways() throws InterruptedException {
        final List<ITermVar> args = IntStream.range(0, params().size()).mapToObj(idx -> B.newVar("", "arg" + idx))
                .collect(Collectors.toList());
        final ApplyResult applyResult;
        try {
            if((applyResult = RuleUtil.apply(PersistentUniDisunifier.Immutable.of(), (Rule) this, args, null,
                    ApplyMode.STRICT, Safety.SAFE).orElse(null)) == null) {
                // We could not apply the rule to the given variables,
                // this rule is not unconditional
                return Optional.empty();
            }
        } catch(Delay d) {
            return Optional.empty();
        }
        if(applyResult.guard().isPresent()) {
            // This rule is not unconditional
            return Optional.empty();
        }
        // Return whether the rule is always true or false; otherwise nothing
        return Constraints.trivial(body());
    }


    private volatile Set.Immutable<ITermVar> freeVars;

    public Set.Immutable<ITermVar> freeVars() {
        Set.Immutable<ITermVar> result = freeVars;
        if(freeVars == null) {
            final Set.Transient<ITermVar> _freeVars = CapsuleUtil.transientSet();
            doVisitFreeVars(_freeVars::__insert);
            result = _freeVars.freeze();
            freeVars = result;
        }
        return result;
    }

    public void visitFreeVars(Action1<ITermVar> onFreeVar) {
        freeVars().forEach(onFreeVar::apply);
    }

    private void doVisitFreeVars(Action1<ITermVar> onFreeVar) {
        final Set.Immutable<ITermVar> paramVars = paramVars();
        body().visitFreeVars(v -> {
            if(!paramVars.contains(v)) {
                onFreeVar.apply(v);
            }
        });
    }

    protected Rule setFreeVars(Set.Immutable<ITermVar> freeVars) {
        this.freeVars = freeVars;
        return (Rule) this;
    }

    /**
     * Apply capture avoiding substitution.
     */
    public Rule apply(ISubstitution.Immutable subst) {
        ISubstitution.Immutable localSubst = subst.removeAll(paramVars()).retainAll(freeVars());
        if(localSubst.isEmpty()) {
            return (Rule) this;
        }

        List<Pattern> params = this.params();
        IConstraint body = this.body();
        ICompleteness.Immutable bodyCriticalEdges = this.bodyCriticalEdges();
        Set.Immutable<ITermVar> freeVars = this.freeVars;

        if(freeVars != null) {
            // before renaming is included in localSubst
            freeVars = freeVars.__removeAll(localSubst.domainSet()).__insertAll(localSubst.rangeSet());
        }

        final FreshVars fresh = new FreshVars(localSubst.domainSet(), localSubst.rangeSet(), freeVars());
        final IRenaming ren = fresh.fresh(paramVars());
        fresh.fix();

        if(!ren.isEmpty()) {
            params = params().stream().map(p -> p.apply(ren)).collect(ImmutableList.toImmutableList());
            localSubst = ren.asSubstitution().compose(localSubst);
        }

        body = body.apply(localSubst);
        if(bodyCriticalEdges != null) {
            bodyCriticalEdges = bodyCriticalEdges.apply(localSubst);
        }

        return Rule.of(name(), params, body).withBodyCriticalEdges(bodyCriticalEdges).setFreeVars(freeVars);
    }

    /**
     * Apply unguarded substitution, which may result in capture.
     */
    public Rule unsafeApply(ISubstitution.Immutable subst) {
        ISubstitution.Immutable localSubst = subst.removeAll(paramVars());
        if(localSubst.isEmpty()) {
            return (Rule) this;
        }

        List<Pattern> params = this.params();
        IConstraint body = this.body();
        ICompleteness.Immutable bodyCriticalEdges = this.bodyCriticalEdges();

        body = body.unsafeApply(localSubst);
        if(bodyCriticalEdges != null) {
            bodyCriticalEdges = bodyCriticalEdges.apply(localSubst);
        }

        return Rule.of(name(), params, body).withBodyCriticalEdges(bodyCriticalEdges);
    }


    /**
     * Apply variable renaming.
     */
    public Rule apply(IRenaming subst) {
        List<Pattern> params = this.params();
        IConstraint body = this.body();
        ICompleteness.Immutable bodyCriticalEdges = this.bodyCriticalEdges();

        params = params().stream().map(p -> p.apply(subst)).collect(ImmutableList.toImmutableList());
        body = body.apply(subst);
        if(bodyCriticalEdges != null) {
            bodyCriticalEdges = bodyCriticalEdges.apply(subst);
        }

        return Rule.of(name(), params, body).withBodyCriticalEdges(bodyCriticalEdges);
    }


    public String toString(TermFormatter termToString) {
        final StringBuilder sb = new StringBuilder();
        if(!label().isEmpty()) {
            sb.append("[").append(label()).append("] ");
        }
        if(name().isEmpty()) {
            sb.append("{ ");
        } else {
            sb.append(name()).append("(");
        }
        sb.append(params().stream().map(Pattern::toString).collect(Collectors.joining(", ")));
        if(!name().isEmpty()) {
            sb.append(")");
        }
        sb.append(" :- ");
        sb.append(body().toString(termToString));
        if(name().isEmpty()) {
            sb.append(" }");
        } else {
            sb.append(".");
        }
        return sb.toString();
    }

    @Override public String toString() {
        return toString(ITerm::toString);
    }


    /**
     * Note: this comparator imposes orderings that are inconsistent with equals.
     */
    public static final LeftRightOrder leftRightPatternOrdering = new LeftRightOrder();

    /**
     * Note: this comparator imposes orderings that are inconsistent with equals.
     */
    public static class LeftRightOrder {

        public Optional<Integer> compare(Rule r1, Rule r2) {
            final Pattern p1 = P.newTuple(r1.params());
            final Pattern p2 = P.newTuple(r2.params());
            return Pattern.leftRightOrdering.compare(p1, p2);
        }

        public Comparator<Rule> asComparator() {
            return (r1, r2) -> LeftRightOrder.this.compare(r1, r2).orElse(0);
        }

    }

}