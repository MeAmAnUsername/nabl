package mb.statix.concurrent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

import org.metaborg.util.collection.CapsuleUtil;
import org.metaborg.util.future.AggregateFuture;
import org.metaborg.util.future.CompletableFuture;
import org.metaborg.util.future.ICompletableFuture;
import org.metaborg.util.future.IFuture;
import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;
import org.metaborg.util.task.NullCancel;
import org.metaborg.util.task.NullProgress;
import org.metaborg.util.tuple.Tuple2;
import org.metaborg.util.unit.Unit;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import io.usethesource.capsule.Set;
import mb.nabl2.terms.ITerm;
import mb.nabl2.terms.stratego.TermIndex;
import mb.nabl2.terms.substitution.IReplacement;
import mb.nabl2.terms.substitution.Replacement;
import mb.nabl2.terms.unification.ud.IUniDisunifier;
import mb.p_raffrayi.ITypeChecker;
import mb.p_raffrayi.ITypeCheckerContext;
import mb.p_raffrayi.IUnitResult;
import mb.p_raffrayi.impl.Result;
import mb.scopegraph.patching.IPatchCollection;
import mb.statix.scopegraph.Scope;
import mb.statix.solver.Delay;
import mb.statix.solver.IState.Immutable;
import mb.statix.solver.ITermProperty;
import mb.statix.solver.completeness.Completeness;
import mb.statix.solver.log.IDebugContext;
import mb.statix.solver.persistent.SolverResult;
import mb.statix.solver.persistent.State;
import mb.statix.spec.ApplyMode;
import mb.statix.spec.ApplyMode.Safety;
import mb.statix.spec.ApplyResult;
import mb.statix.spec.Rule;
import mb.statix.spec.RuleUtil;
import mb.statix.spec.Spec;

public abstract class AbstractTypeChecker<R extends ITypeChecker.IOutput<Scope, ITerm, ITerm>>
        implements ITypeChecker<Scope, ITerm, ITerm, R, SolverState> {

    private static final ILogger logger = LoggerUtils.logger(AbstractTypeChecker.class);

    protected final Spec spec;
    protected final IDebugContext debug;

    protected AbstractTypeChecker(Spec spec, IDebugContext debug) {
        this.spec = spec;
        this.debug = debug;
    }

    private StatixSolver solver;
    private IFuture<SolverResult> solveResult;
    private final Multimap<ITerm, ICompletableFuture<ITerm>> pendingData = ArrayListMultimap.create();

    private boolean snapshotTaken = false;

    protected Scope makeSharedScope(ITypeCheckerContext<Scope, ITerm, ITerm> context, String name) {
        final Scope s = context.stableFreshScope(name, Collections.emptyList(), true);
        context.setDatum(s, s);
        context.shareLocal(s);
        return s;
    }

    protected
            IFuture<Map<String, IUnitResult<Scope, ITerm, ITerm, Result<Scope, ITerm, ITerm, GroupResult, SolverState>>>>
            runGroups(ITypeCheckerContext<Scope, ITerm, ITerm> context, Map<String, IStatixGroup> groups,
                    List<Scope> parentScopes) {
        if(groups.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        final List<IFuture<Tuple2<String, IUnitResult<Scope, ITerm, ITerm, Result<Scope, ITerm, ITerm, GroupResult, SolverState>>>>> results =
                new ArrayList<>();
        for(Map.Entry<String, IStatixGroup> entry : groups.entrySet()) {
            final String key = entry.getKey();
            final IFuture<IUnitResult<Scope, ITerm, ITerm, Result<Scope, ITerm, ITerm, GroupResult, SolverState>>> result =
                    context.add(key, new GroupTypeChecker(entry.getValue(), spec, debug), parentScopes,
                            entry.getValue().changed());
            results.add(result.thenApply(r -> Tuple2.of(key, r)).whenComplete((r, ex) -> {
                logger.debug("checker {}: group {} returned.", context.id(), key);
            }));
        }
        return AggregateFuture.of(results)
                .thenApply(es -> es.stream().collect(Collectors.toMap(Entry::getKey, Entry::getValue)))
                .whenComplete((r, ex) -> {
                    logger.debug("checker {}: all groups returned.", context.id());
                });
    }

    protected
            IFuture<Map<String, IUnitResult<Scope, ITerm, ITerm, Result<Scope, ITerm, ITerm, UnitResult, SolverState>>>>
            runUnits(ITypeCheckerContext<Scope, ITerm, ITerm> context, Map<String, IStatixUnit> units,
                    List<Scope> parentScopes) {
        if(units.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        final List<IFuture<Tuple2<String, IUnitResult<Scope, ITerm, ITerm, Result<Scope, ITerm, ITerm, UnitResult, SolverState>>>>> results =
                new ArrayList<>();
        for(Map.Entry<String, IStatixUnit> entry : units.entrySet()) {
            final String key = entry.getKey();
            final IFuture<IUnitResult<Scope, ITerm, ITerm, Result<Scope, ITerm, ITerm, UnitResult, SolverState>>> result =
                    context.add(key, new UnitTypeChecker(entry.getValue(), spec, debug), parentScopes,
                            entry.getValue().changed());
            results.add(result.thenApply(r -> Tuple2.of(key, r)).whenComplete((r, ex) -> {
                logger.debug("checker {}: unit {} returned.", context.id(), key);
            }));
        }
        return AggregateFuture.of(results)
                .thenApply(es -> es.stream().collect(Collectors.toMap(Entry::getKey, Entry::getValue)))
                .whenComplete((r, ex) -> {
                    logger.debug("checker {}: all units returned.", context.id());
                });
    }

    protected IFuture<Map<String, IUnitResult<Scope, ITerm, ITerm, Unit>>> runLibraries(
            ITypeCheckerContext<Scope, ITerm, ITerm> context, Map<String, IStatixLibrary> libraries,
            Scope parentScope) {
        if(libraries.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        final List<IFuture<Tuple2<String, IUnitResult<Scope, ITerm, ITerm, Unit>>>> results = new ArrayList<>();
        for(Map.Entry<String, IStatixLibrary> entry : libraries.entrySet()) {
            final String key = entry.getKey();
            IStatixLibrary library = entry.getValue();
            final IFuture<IUnitResult<Scope, ITerm, ITerm, Unit>> result =
                    context.add(key, library, Arrays.asList(parentScope));
            results.add(result.thenApply(r -> Tuple2.of(key, r)).whenComplete((r, ex) -> {
                logger.debug("checker {}: library {} returned.", context.id(), key);
            }));
        }
        return AggregateFuture.of(results)
                .thenApply(es -> es.stream().collect(Collectors.toMap(Entry::getKey, Entry::getValue)))
                .whenComplete((r, ex) -> {
                    logger.debug("checker {}: all libraries returned.", context.id());
                });
    }

    protected IFuture<SolverResult> runSolver(ITypeCheckerContext<Scope, ITerm, ITerm> context, Optional<Rule> rule,
            Optional<SolverState> initialState, List<Scope> scopes) {
        if(initialState.isPresent()) {
            return runSolver(context, initialState.get());
        } else {
            return runSolver(context, rule, scopes);
        }
    }

    protected IFuture<SolverResult> runSolver(ITypeCheckerContext<Scope, ITerm, ITerm> context, Optional<Rule> rule,
            List<Scope> scopes) {
        if(!rule.isPresent()) {
            for(Scope scope : scopes) {
                context.initScope(scope, Collections.emptyList(), false);
            }
            return CompletableFuture.completedFuture(SolverResult.of(spec));
        }
        final /*IState.*/Immutable unitState = State.of().withResource(context.id());
        final ApplyResult applyResult;
        try {
            // UNSAFE : we assume the resource of spec variables is empty and of state variables non-empty
            if((applyResult =
                    RuleUtil.apply(unitState.unifier(), rule.get(), scopes, null, ApplyMode.STRICT, Safety.UNSAFE)
                            .orElse(null)) == null) {
                return CompletableFuture.completedExceptionally(
                        new IllegalArgumentException("Cannot apply initial rule to root scope."));
            }
        } catch(Delay delay) {
            return CompletableFuture.completedExceptionally(
                    new IllegalArgumentException("Cannot apply initial rule to root scope.", delay));
        }

        solver = new StatixSolver(applyResult.body(), spec, unitState, applyResult.criticalEdges(), debug,
                new NullProgress(), new NullCancel(), context, 0);
        solveResult = solver.solve(scopes);

        return finish(solveResult, context.id());
    }

    protected IFuture<SolverResult> runSolver(ITypeCheckerContext<Scope, ITerm, ITerm> context,
            SolverState initialState) {
        solver = new StatixSolver(initialState, spec, debug, new NullProgress(), new NullCancel(), context, 0);
        solveResult = solver.continueSolve();
        pendingData.forEach((d, future) -> solver.getExternalRepresentation(d).whenComplete(future::complete));
        pendingData.clear();

        return finish(solveResult, context.id());
    }

    private IFuture<SolverResult> finish(IFuture<SolverResult> future, String id) {
        return future.thenApply(r -> {
            logger.debug("checker {}: solver returned.", id);
            if(snapshotTaken) {
                solver = null; // gc solver
            }
            return r; // FIXME minimize result to what is externally visible
                      //       note that this can make debugging harder, so perhaps optional?
        });

    }

    protected SolverResult patch(SolverResult previousResult, IPatchCollection.Immutable<Scope> patches) {
        if(patches.isIdentity()) {
            return previousResult;
        }

        // Convert patches to replacement
        final Replacement.Builder builder = Replacement.builder();
        patches.patches().asMap().forEach((newScope, oldScope) -> {
            builder.put(oldScope, newScope);
        });
        final IReplacement repl = builder.build();

        // Patch unifier
        final /*IState.*/Immutable oldState = previousResult.state();
        final IUniDisunifier.Immutable unifier = oldState.unifier().replace(repl);

        // Patch properties
        // Note that the key is always a termindex * {Type(), Ref() or Prop(name)}, and hence does not need patching
        final io.usethesource.capsule.Map.Transient<Tuple2<TermIndex, ITerm>, ITermProperty> props =
                CapsuleUtil.transientMap();
        oldState.termProperties().forEach((k, v) -> props.__put(k, v.replace(repl)));

        // Patch scope set.
        // TODO: required, or only set for locally created scopes?
        final Set.Transient<Scope> scopes = oldState.scopes().asTransient();
        patches.patchDomain().forEach(s -> {
            if(scopes.__remove(s)) {
                scopes.__insert(patches.patch(s));
            }
        });

        // state.scopeGraph() property set later with new result, no need to patch here.

        // TODO: patch removed edges? messages? delays? completeness?

        // @formatter:off
        final /*IState.*/Immutable newState = State.builder().from(oldState)
            .__scopes(scopes.freeze())
            .unifier(unifier)
            .termProperties(props.freeze())
            .build();
        // @formatter:on
        return previousResult.withState(newState);
    }

    @Override public IFuture<ITerm> getExternalDatum(ITerm datum) {
        if(datum.isGround()) {
            return CompletableFuture.completedFuture(datum);
        } else if(solver != null) {
            return solver.getExternalRepresentation(datum);
        } else if(solveResult != null) {
            return solveResult.thenCompose(r -> {
                final IUniDisunifier.Immutable unifier = r.state().unifier();
                if(unifier.isGround(datum)) {
                    return CompletableFuture.completedFuture(unifier.findRecursive(datum));
                } else {
                    return CompletableFuture.noFuture();
                }
            });
        } else {
            final ICompletableFuture<ITerm> future = new CompletableFuture<>();
            pendingData.put(datum, future);
            return future;
        }
    }

    @Override public ITerm internalData(ITerm datum) {
        if(solver != null) {
            return solver.internalData(datum);
        }
        return datum;
    }

    @Override public SolverState snapshot() {
        if(solver == null) {
            return SolverState.of(State.of(), Completeness.Immutable.of(), Sets.newHashSet(), null, Arrays.asList(),
                    Maps.newHashMap(), CapsuleUtil.immutableSet());
        }
        final SolverState snapshot = solver.snapshot();
        snapshotTaken = true;
        if(solveResult.isDone()) {
            solver = null;
        }

        return snapshot;
    }

}
