package mb.statix.spec;

import static mb.nabl2.terms.matching.TermPattern.P;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.immutables.value.Value;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import mb.nabl2.terms.ITerm;
import mb.nabl2.terms.ITermVar;
import mb.nabl2.terms.matching.Pattern;
import mb.nabl2.terms.substitution.ISubstitution;
import mb.nabl2.terms.substitution.ISubstitution.Immutable;
import mb.nabl2.util.ImmutableTuple2;
import mb.nabl2.util.ImmutableTuple3;
import mb.nabl2.util.TermFormatter;
import mb.nabl2.util.Tuple2;
import mb.nabl2.util.Tuple3;
import mb.statix.solver.Completeness;
import mb.statix.solver.Delay;
import mb.statix.solver.IConstraint;
import mb.statix.solver.Solver;
import mb.statix.solver.SolverResult;
import mb.statix.solver.State;
import mb.statix.solver.log.NullDebugContext;
import mb.statix.taico.module.IModule;
import mb.statix.taico.scopegraph.IOwnableScope;
import mb.statix.taico.scopegraph.OwnableScope;
import mb.statix.taico.solver.MState;

/**
 * Class which describes a statix rule.
 * 
 * <pre>ruleName(paramVars) :- {bodyVars} constraints.</pre>
 * or
 * <pre>{ paramVars :- {bodyVars} constraints }</pre>
 */
@Value.Immutable
public abstract class ARule implements IRule {

    @Value.Parameter public abstract String name();

    @Value.Parameter public abstract List<Pattern> params();

    @Value.Parameter public abstract Set<ITermVar> bodyVars();

    @Value.Parameter public abstract List<IConstraint> body();

    @Value.Lazy public Optional<Boolean> isAlways(Spec spec) throws InterruptedException {
        State state = State.of(spec);
        List<ITerm> args = Lists.newArrayList();
        for(@SuppressWarnings("unused") Pattern param : params()) {
            final Tuple2<ITermVar, State> stateAndVar = state.freshVar("arg");
            args.add(stateAndVar._1());
            state = stateAndVar._2();
        }
        Tuple3<State, Set<ITermVar>, Set<IConstraint>> stateAndInst;
        try {
            if((stateAndInst = apply(args, state).orElse(null)) == null) {
                return Optional.of(false);
            }
        } catch(Delay e) {
            return Optional.of(false);
        }
        state = stateAndInst._1();
        final Set<ITermVar> instVars = stateAndInst._2();
        final Set<IConstraint> instBody = stateAndInst._3();
        try {
            Optional<SolverResult> solverResult =
                    Solver.entails(state, instBody, new Completeness(), instVars, new NullDebugContext());
            if(solverResult.isPresent()) {
                return Optional.of(true);
            } else {
                return Optional.of(false);
            }
        } catch(Delay d) {
            return Optional.empty();
        }
    }

    @Override
    public IRule apply(ISubstitution.Immutable subst) {
        final ISubstitution.Immutable bodySubst = subst.removeAll(paramVars()).removeAll(bodyVars());
        final List<IConstraint> newBody = body().stream().map(c -> c.apply(bodySubst)).collect(Collectors.toList());
        return Rule.of(name(), params(), bodyVars(), newBody);
    }

    @Override
    public Optional<Tuple3<State, Set<ITermVar>, Set<IConstraint>>> apply(List<ITerm> args, State state) throws Delay {
        final ISubstitution.Transient subst;
        final Optional<Immutable> matchResult = P.match(params(), args, state.unifier()).matchOrThrow(r -> r, vars -> {
            throw Delay.ofVars(vars);
        });
        if((subst = matchResult.map(u -> u.melt()).orElse(null)) == null) {
            return Optional.empty();
        }
        State newState = state;
        final ImmutableSet.Builder<ITermVar> freshBodyVars = ImmutableSet.builder();
        for(ITermVar var : bodyVars()) {
            final Tuple2<ITermVar, State> vs = newState.freshVar(var.getName());
            subst.put(var, vs._1());
            freshBodyVars.add(vs._1());
            newState = vs._2();
        }
        final ISubstitution.Immutable isubst = subst.freeze();
        final Set<IConstraint> newBody = body().stream().map(c -> c.apply(isubst)).collect(Collectors.toSet());
        return Optional.of(ImmutableTuple3.of(newState, freshBodyVars.build(), newBody));
    }
    
    @Override
    public Optional<Tuple2<Set<ITermVar>, Set<IConstraint>>> apply(List<ITerm> args, MState state) throws Delay {
        if (isModuleBoundary()) throw new IllegalStateException("Cannot apply as non module boundary, since this rule is a module boundary!");
        
        final ISubstitution.Transient subst;
        final Optional<Immutable> matchResult = P.match(params(), args, state.unifier()).matchOrThrow(r -> r, vars -> {
            throw Delay.ofVars(vars);
        });
        if((subst = matchResult.map(u -> u.melt()).orElse(null)) == null) {
            return Optional.empty();
        }
        
        final ImmutableSet.Builder<ITermVar> freshBodyVars = ImmutableSet.builder();
        for(ITermVar var : bodyVars()) {
            final ITermVar term = state.freshVar(var.getName());
            subst.put(var, term);
            freshBodyVars.add(term);
        }
        final ISubstitution.Immutable isubst = subst.freeze();
        final Set<IConstraint> newBody = body().stream().map(c -> c.apply(isubst)).collect(Collectors.toSet());
        return Optional.of(ImmutableTuple2.of(freshBodyVars.build(), newBody));
    }
    
    /**
     * Applies the given arguments to this rule.
     * 
     * <p>The state returned by this method is the state of the child module.</p>
     * 
     * @param args
     *      the arguments to apply
     * @param state
     *      the current state
     * 
     * @return
     *      a tuple with the state, the new variables and the set of new constraints. If the
     *      arguments do not match the parameters, an empty optional is returned
     * 
     * @throws Delay
     *      If the arguments cannot be matched to the parameters of this rule because one or more
     *      terms are not ground.
     * @throws IllegalStateException
     *      If this rule is not a module boundary.
     */
    public Optional<Tuple3<MState, Set<ITermVar>, Set<IConstraint>>> applyModuleBoundary(List<ITerm> args, MState state) throws Delay {
        if (!isModuleBoundary()) throw new IllegalStateException("Cannot appy as module boundary to a non module boundary rule!");
        
        final ISubstitution.Transient subst;
        final Optional<Immutable> matchResult = P.match(params(), args, state.unifier()).matchOrThrow(r -> r, vars -> {
            throw Delay.ofVars(vars);
        });
        if((subst = matchResult.map(u -> u.melt()).orElse(null)) == null) {
            return Optional.empty();
        }
        
        //We don't always want to statically store the child relation. We want to base this on the current owner.
        List<IOwnableScope> canExtend = new ArrayList<>();
        for (ITerm term : args) {
            OwnableScope scope = OwnableScope.ownableMatcher(state.manager()::getModule).match(term).orElse(null);
            if (scope != null) canExtend.add(scope);
        }
        
        IModule child = state.owner().createChild(canExtend);
        state = new MState(state.manager(), state.coordinator(), child, state.spec());
        
        final ImmutableSet.Builder<ITermVar> freshBodyVars = ImmutableSet.builder();
        for(ITermVar var : bodyVars()) {
            final ITermVar term = state.freshVar(var.getName());
            subst.put(var, term);
            freshBodyVars.add(term);
        }
        final ISubstitution.Immutable isubst = subst.freeze();
        final Set<IConstraint> newBody = body().stream().map(c -> c.apply(isubst)).collect(Collectors.toSet());
        return Optional.of(ImmutableTuple3.of(state, freshBodyVars.build(), newBody));
    }
    
    /**
     * @return
     *      true if this rule crosses a module boundary, false otherwise
     */
    public final boolean isModuleBoundary() {
        return name().startsWith("modbound_");
    }

    /**
     * Formats this rule where constraints are formatted with the given TermFormatter.
     * 
     * <pre>&lt;name&gt;(&lt;params&gt;) [:- [{&lt;bodyVars&gt;}] &lt;constraints&gt;].</pre>
     * <pre>{ &lt;params&gt; [:- [{&lt;bodyVars&gt;}] &lt;constraints&gt;] }</pre>
     * 
     * @param termToString
     *      the term formatter to format constraints with
     * 
     * @return
     *      the string
     */
    public String toString(TermFormatter termToString) {
        final StringBuilder sb = new StringBuilder();
        if(name().isEmpty()) {
            sb.append("{ ").append(params());
        } else {
            sb.append(name()).append("(").append(params()).append(")");
        }
        if(!body().isEmpty()) {
            sb.append(" :- ");
            if(!bodyVars().isEmpty()) {
                sb.append("{").append(bodyVars()).append("} ");
            }
            sb.append(IConstraint.toString(body(), termToString.removeAll(bodyVars())));
        }
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
    public static java.util.Comparator<IRule> leftRightPatternOrdering = new LeftRightPatternOrder();

    /**
     * Note: this comparator imposes orderings that are inconsistent with equals.
     */
    private static class LeftRightPatternOrder implements Comparator<IRule> {

        @Override public int compare(IRule r1, IRule r2) {
            final Pattern p1 = P.newTuple(r1.params());
            final Pattern p2 = P.newTuple(r2.params());
            return Pattern.leftRightOrdering.compare(p1, p2);
        }

    }

}