package mb.p_raffrayi.impl;

import static com.google.common.collect.Streams.stream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.metaborg.util.collection.CapsuleUtil;
import org.metaborg.util.functions.Function1;
import org.metaborg.util.functions.Function2;
import org.metaborg.util.future.AggregateFuture;
import org.metaborg.util.future.AggregateFuture.SC;
import org.metaborg.util.future.CompletableFuture;
import org.metaborg.util.future.Futures;
import org.metaborg.util.future.ICompletableFuture;
import org.metaborg.util.future.IFuture;
import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;
import org.metaborg.util.unit.Unit;

import com.google.common.collect.Sets;

import io.usethesource.capsule.Set;
import mb.p_raffrayi.IIncrementalTypeCheckerContext;
import mb.p_raffrayi.IRecordedQuery;
import mb.p_raffrayi.IScopeGraphLibrary;
import mb.p_raffrayi.ITypeChecker;
import mb.p_raffrayi.IUnitResult;
import mb.p_raffrayi.IUnitResult.TransitionTrace;
import mb.p_raffrayi.actors.IActor;
import mb.p_raffrayi.actors.IActorRef;
import mb.p_raffrayi.impl.diff.AddingDiffer;
import mb.p_raffrayi.impl.diff.IDifferContext;
import mb.p_raffrayi.impl.diff.IDifferOps;
import mb.p_raffrayi.impl.diff.IScopeGraphDiffer;
import mb.p_raffrayi.impl.diff.MatchingDiffer;
import mb.p_raffrayi.impl.diff.ScopeGraphDiffer;
import mb.p_raffrayi.impl.diff.StaticDifferContext;
import mb.p_raffrayi.impl.envdiff.AddedEdge;
import mb.p_raffrayi.impl.envdiff.EnvDiffer;
import mb.p_raffrayi.impl.envdiff.External;
import mb.p_raffrayi.impl.envdiff.IEnvDiffer;
import mb.p_raffrayi.impl.envdiff.RemovedEdge;
import mb.p_raffrayi.impl.tokens.Activate;
import mb.p_raffrayi.impl.tokens.Confirm;
import mb.p_raffrayi.impl.tokens.IWaitFor;
import mb.p_raffrayi.impl.tokens.Query;
import mb.p_raffrayi.nameresolution.DataLeq;
import mb.p_raffrayi.nameresolution.DataWf;
import mb.scopegraph.ecoop21.LabelOrder;
import mb.scopegraph.ecoop21.LabelWf;
import mb.scopegraph.oopsla20.IScopeGraph;
import mb.scopegraph.oopsla20.IScopeGraph.Immutable;
import mb.scopegraph.oopsla20.diff.BiMap;
import mb.scopegraph.oopsla20.path.IResolutionPath;
import mb.scopegraph.oopsla20.reference.EdgeOrData;
import mb.scopegraph.oopsla20.reference.Env;
import mb.scopegraph.oopsla20.reference.ScopeGraph;
import mb.scopegraph.oopsla20.terms.newPath.ResolutionPath;
import mb.scopegraph.oopsla20.terms.newPath.ScopePath;

class TypeCheckerUnit<S, L, D, R> extends AbstractUnit<S, L, D, R>
        implements IIncrementalTypeCheckerContext<S, L, D, R> {


    private static final ILogger logger = LoggerUtils.logger(TypeCheckerUnit.class);

    private final ITypeChecker<S, L, D, R> typeChecker;
    private final boolean changed;
    private final @Nullable IUnitResult<S, L, D, R> previousResult;

    private volatile UnitState state;

    private final BiMap.Transient<S> matchedBySharing = BiMap.Transient.of();

    private final IScopeGraph.Transient<S, L, D> localScopeGraph = ScopeGraph.Transient.of();

    private final Set.Transient<String> addedUnitIds = CapsuleUtil.transientSet();

    private final ICompletableFuture<Unit> whenActive = new CompletableFuture<>();
    private final ICompletableFuture<Optional<BiMap.Immutable<S>>> confirmationResult = new CompletableFuture<>();

    private IEnvDiffer<S, L, D> envDiffer;

    //    private IConfirmation<S, L, D> confirmation = new TrivialConfirmation();
    private IConfirmation<S, L, D> confirmation = new LazyConfirmation();

    TypeCheckerUnit(IActor<? extends IUnit<S, L, D, R>> self, @Nullable IActorRef<? extends IUnit<S, L, D, ?>> parent,
            IUnitContext<S, L, D> context, ITypeChecker<S, L, D, R> unitChecker, Iterable<L> edgeLabels,
            boolean inputChanged, IUnitResult<S, L, D, R> previousResult) {
        super(self, parent, context, edgeLabels);
        this.typeChecker = unitChecker;
        this.changed = inputChanged;
        this.previousResult = previousResult;
        this.state = UnitState.INIT_UNIT;
    }

    TypeCheckerUnit(IActor<? extends IUnit<S, L, D, R>> self, @Nullable IActorRef<? extends IUnit<S, L, D, ?>> parent,
            IUnitContext<S, L, D> context, ITypeChecker<S, L, D, R> unitChecker, Iterable<L> edgeLabels) {
        this(self, parent, context, unitChecker, edgeLabels, true, null);
    }


    @Override protected IFuture<D> getExternalDatum(D datum) {
        return typeChecker.getExternalDatum(datum);
    }

    ///////////////////////////////////////////////////////////////////////////
    // IBroker2UnitProtocol interface, called by IBroker implementations
    ///////////////////////////////////////////////////////////////////////////

    @Override public IFuture<IUnitResult<S, L, D, R>> _start(List<S> rootScopes) {
        assertInState(UnitState.INIT_UNIT);
        resume();

        doStart(rootScopes);
        state = UnitState.INIT_TC;
        final IFuture<R> result = this.typeChecker.run(this, rootScopes).whenComplete((r, ex) -> {
            if(state == UnitState.INIT_TC) {
                stateTransitionTrace = TransitionTrace.INITIALLY_STARTED;
            }
            state = UnitState.DONE;
        });

        // Start phantom units for all units that have not yet been restarted
        if(previousResult != null) {
            for(String removedId : Sets.difference(previousResult.subUnitResults().keySet(), addedUnitIds)) {
                final IUnitResult<S, L, D, ?> subResult = previousResult.subUnitResults().get(removedId);
                this.<Unit>doAddSubUnit(removedId,
                        (subself, subcontext) -> new PhantomUnit<>(subself, self, subcontext, edgeLabels, subResult),
                        new ArrayList<>(), true);
            }
        }

        if(state == UnitState.INIT_TC) {
            // runIncremental not called, so start eagerly
            doRestart();
        } else if(state == UnitState.DONE) {
            // Completed synchronously
            whenActive.complete(Unit.unit);
            confirmationResult.complete(Optional.of(BiMap.Immutable.of()));
        }

        if(!whenActive.isDone()) {
            final Activate<S, L, D> activate = Activate.of(self, whenActive);
            waitFor(activate, self);
            whenActive.whenComplete((u, ex) -> {
                granted(activate, self);
                resume();
            });
        }

        return doFinish(result);
    }

    ///////////////////////////////////////////////////////////////////////////
    // IUnit2UnitProtocol interface, called by IUnit implementations
    ///////////////////////////////////////////////////////////////////////////

    @Override public IFuture<Optional<BiMap.Immutable<S>>> _confirm(ScopePath<S, L> path, LabelWf<L> labelWF,
            DataWf<S, L, D> dataWF, boolean prevEnvEmpty) {
        // TODO assert confirmation enabled
        stats.incomingConfirmations++;
        final IActorRef<? extends IUnit<S, L, D, ?>> sender = self.sender(TYPE);
        final ICompletableFuture<Optional<BiMap.Immutable<S>>> result = new CompletableFuture<>();
        whenActive.whenComplete((__, ex) -> {
            if(ex != null && ex != Release.instance) {
                result.completeExceptionally(ex);
            } else {
                confirmation.confirm(sender, path, labelWF, dataWF, prevEnvEmpty).whenComplete(result::complete);
            }
        });
        return result;
    }

    @Override public IFuture<Env<S, L, D>> _queryPrevious(ScopePath<S, L> path, LabelWf<L> labelWF,
            DataWf<S, L, D> dataWF, LabelOrder<L> labelOrder, DataLeq<S, L, D> dataEquiv) {
        return doQueryPrevious(previousResult.scopeGraph(), path, labelWF, dataWF, labelOrder, dataEquiv);
    }

    @Override public IFuture<Optional<S>> _match(S previousScope) {
        assertOwnScope(previousScope);
        if(matchedBySharing.containsValue(previousScope)) {
            return CompletableFuture.completedFuture(Optional.of(matchedBySharing.getValue(previousScope)));
        }
        return super._match(previousScope);
    }

    @Override public IFuture<StateSummary<S>> _requireRestart() {
        if(state.equals(UnitState.ACTIVE)
                || (state == UnitState.DONE && stateTransitionTrace != TransitionTrace.RELEASED)) {
            return CompletableFuture.completedFuture(StateSummary.restart());
        }

        if(state.equals(UnitState.RELEASED)
                || (state == UnitState.DONE && stateTransitionTrace == TransitionTrace.RELEASED)) {
            return CompletableFuture.completedFuture(StateSummary.released(BiMap.Immutable.from(matchedBySharing)));
        }

        // When these patches are used, *all* involved units re-use their old scope graph.
        // Hence only patching the root scopes is sufficient.
        // TODO Re-validate when a more sophisticated confirmation algorithm is implemented.
        return CompletableFuture.completedFuture(StateSummary.release(BiMap.Immutable.from(matchedBySharing)));
    }

    @Override public void _restart() {
        if(doRestart()) {
            stateTransitionTrace = TransitionTrace.RESTARTED;
        }
    }

    @Override public void _release(BiMap.Immutable<S> patches) {
        // TODO: what if message received, but not part of 'real' deadlock cluster?
        // Then in state `ACTIVE`, and hence doRelease won't do anything automatically?
        doRelease(patches);
    }

    ///////////////////////////////////////////////////////////////////////////
    // ITypeCheckerContext interface, called by ITypeChecker implementations
    ///////////////////////////////////////////////////////////////////////////

    // NB. Invoke methods via `local` so that we have the same scheduling & ordering
    // guarantees as for remote calls.

    @Override public String id() {
        return self.id();
    }

    @Override public <Q> IFuture<IUnitResult<S, L, D, Q>> add(String id, ITypeChecker<S, L, D, Q> unitChecker,
            List<S> rootScopes, boolean changed) {
        assertActive();

        // No previous result for subunit
        if(this.previousResult == null || !this.previousResult.subUnitResults().containsKey(id)) {
            this.addedUnitIds.__insert(id);
            return ifActive(this.<Q>doAddSubUnit(id, (subself, subcontext) -> {
                return new TypeCheckerUnit<>(subself, self, subcontext, unitChecker, edgeLabels);
            }, rootScopes, false)._2());
        }

        @SuppressWarnings("unchecked") final IUnitResult<S, L, D, Q> subUnitPreviousResult =
                (IUnitResult<S, L, D, Q>) this.previousResult.subUnitResults().get(id);


        // When a scope is shared, the shares must be consistent.
        // Also, it is not necessary that shared scopes are reachable from the root scopes
        // (A unit started by the Broker does not even have root scopes)
        // Therefore we enforce here that the current root scopes and the previous ones match.
        List<S> previousRootScopes = subUnitPreviousResult.rootScopes();

        int pSize = previousRootScopes.size();
        int cSize = rootScopes.size();
        if(cSize != pSize) {
            logger.error("Unit {} adds subunit {} with initial state but with different root scope count.");
            throw new IllegalStateException("Different root scope count.");
        }

        final BiMap.Transient<S> req = BiMap.Transient.of();
        for(int i = 0; i < rootScopes.size(); i++) {
            final S cRoot = rootScopes.get(i);
            final S pRoot = previousRootScopes.get(i);
            req.put(cRoot, pRoot);
            if(isOwner(cRoot)) {
                matchedBySharing.put(cRoot, pRoot);
            }
        }

        if(isDifferEnabled()) {
            whenDifferActivated.thenAccept(__ -> {
                if(!differ.matchScopes(req.freeze())) {
                    logger.error("Unit {} adds subunit {} with initial state but with different root scope count.");
                    throw new IllegalStateException("Could not match.");
                }
            });
        }

        this.addedUnitIds.__insert(id);
        final IFuture<IUnitResult<S, L, D, Q>> result = this.<Q>doAddSubUnit(id, (subself, subcontext) -> {
            return new TypeCheckerUnit<>(subself, self, subcontext, unitChecker, edgeLabels, changed,
                    subUnitPreviousResult);
        }, rootScopes, false)._2();

        return ifActive(result);
    }

    @Override public IFuture<IUnitResult<S, L, D, Unit>> add(String id, IScopeGraphLibrary<S, L, D> library,
            List<S> rootScopes) {
        assertActive();

        final IFuture<IUnitResult<S, L, D, Unit>> result = this.<Unit>doAddSubUnit(id, (subself, subcontext) -> {
            return new ScopeGraphLibraryUnit<>(subself, self, subcontext, edgeLabels, library);
        }, rootScopes, true)._2();

        return ifActive(result);
    }

    @Override public void initScope(S root, Iterable<L> labels, boolean sharing) {
        assertActive();

        final List<EdgeOrData<L>> edges = stream(labels).map(EdgeOrData::edge).collect(Collectors.toList());

        doInitShare(self, root, edges, sharing);
    }

    @Override public S freshScope(String baseName, Iterable<L> edgeLabels, boolean data, boolean sharing) {
        assertActive();
        if(!sharing) {
            doImplicitActivate();
        }

        final S scope = doFreshScope(baseName, edgeLabels, data, sharing);

        return scope;
    }

    @Override public void shareLocal(S scope) {
        assertActive();

        doAddShare(self, scope);
    }

    @Override public void setDatum(S scope, D datum) {
        assertActive();

        doSetDatum(scope, datum);
        localScopeGraph.setDatum(scope, datum);
    }

    @Override public void addEdge(S source, L label, S target) {
        assertActive();
        doImplicitActivate();

        doAddEdge(self, source, label, target);
        localScopeGraph.addEdge(source, label, target);
    }

    @Override public void closeEdge(S source, L label) {
        assertActive();

        doCloseLabel(self, source, EdgeOrData.edge(label));
    }

    @Override public void closeScope(S scope) {
        assertActive();

        doCloseScope(self, scope);
    }

    @Override public IFuture<Set<IResolutionPath<S, L, D>>> query(S scope, LabelWf<L> labelWF, LabelOrder<L> labelOrder,
            DataWf<S, L, D> dataWF, DataLeq<S, L, D> dataEquiv, @Nullable DataWf<S, L, D> dataWfInternal,
            @Nullable DataLeq<S, L, D> dataEquivInternal) {
        assertActive();
        doImplicitActivate();

        final ScopePath<S, L> path = new ScopePath<>(scope);
        final IFuture<IQueryAnswer<S, L, D>> result =
                doQuery(self, path, labelWF, labelOrder, dataWF, dataEquiv, dataWfInternal, dataEquivInternal);
        final IFuture<IQueryAnswer<S, L, D>> ret;
        if(result.isDone()) {
            ret = result;
        } else {
            final Query<S, L, D> wf = Query.of(self, path, labelWF, dataWF, labelOrder, dataEquiv, result);
            waitFor(wf, self);
            ret = result.whenComplete((env, ex) -> {
                granted(wf, self);
            });
        }
        stats.localQueries += 1;
        return ifActive(ret).thenApply(ans -> {
            return CapsuleUtil.toSet(ans.env());
        });
    }

    @Override public <Q> IFuture<R> runIncremental(Function1<Boolean, IFuture<Q>> runLocalTypeChecker,
            Function1<R, Q> extractLocal, Function2<Q, BiMap.Immutable<S>, Q> patch,
            Function2<Q, Throwable, IFuture<R>> combine) {
        assertInState(UnitState.INIT_TC);
        state = UnitState.UNKNOWN;
        if(!isIncrementalEnabled() || changed) {
            logger.debug("Unit changed or no previous result was available.");
            stateTransitionTrace = TransitionTrace.INITIALLY_STARTED;
            doRestart();
            return runLocalTypeChecker.apply(false).compose(combine::apply);
        }

        doConfirmQueries();

        // Invariant: added units are marked as changed.
        // Therefore, if unit is not changed, previousResult cannot be null.

        return confirmationResult.thenCompose(patches -> {
            if(patches.isPresent()) {
                assertPreviousResultProvided();
                final Q previousLocalResult = extractLocal.apply(previousResult.analysis());
                return combine.apply(patch.apply(previousLocalResult, patches.get()), null);
            } else {
                return runLocalTypeChecker.apply(true).compose(combine::apply);
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////
    // Implementation -- confirmation, restart and reuse
    ///////////////////////////////////////////////////////////////////////////

    private void doConfirmQueries() {
        assertInState(UnitState.UNKNOWN);
        assertIncrementalEnabled();
        resume();

        if(previousResult == null) {
            logger.error("Cannot confirm queries when no previous result is provided.");
            throw new IllegalStateException("Cannot confirm queries when no previous result is provided.");
        }

        if(previousResult.queries().isEmpty()) {
            doRelease(BiMap.Immutable.of());
            return;
        }

        confirmation.confirm(previousResult.queries()).whenComplete((r, ex) -> {
            if(ex == Release.instance) {
                // Do nothing, unit is already released by deadlock resolution.
            } else if(ex != null) {
                failures.add(ex);
            } else if(!r.isPresent()) {
                // No confirmation, hence restart
                if(doRestart()) {
                    stateTransitionTrace = TransitionTrace.RESTARTED;
                }
            } else {
                doRelease(r.get());
            }
        });
    }

    private void doRelease(BiMap.Immutable<S> patches) {
        assertIncrementalEnabled();
        if(state == UnitState.UNKNOWN) {
            assertPreviousResultProvided();

            // TODO When a unit has active subunits, the matching differ cannot be used, because these units may add/remove edges.
            // However, a full-blown scope graph differ may impose quite some overhead. We should invent a smarter solution for this.
            // - Perhaps only maintain local scope graphs (i.e. no edge sharing), and relay queries to subunits
            //   - Con: very similar to epsilon edges, which degraded performance a lot, and complicated shadowing
            // - Somehow inform differ of local edges and subunit edges, and perform diff based on those.

            // @formatter:off
            final IScopeGraphDiffer<S, L, D> differ = matchedBySharing.isEmpty() ?
                new MatchingDiffer<S, L, D>(differOps(), differContext(), patches) :
                new ScopeGraphDiffer<>(differContext(), new StaticDifferContext<>(previousResult.scopeGraph()), differOps());
            // @formatter:on
            initDiffer(differ, this.rootScopes, previousResult.rootScopes());
            this.envDiffer = new EnvDiffer<>(differ, differOps());

            final IScopeGraph.Transient<S, L, D> newScopeGraph = ScopeGraph.Transient.of();
            previousResult.localScopeGraph().getEdges().forEach((entry, targets) -> {
                final S oldSource = entry.getKey();
                final S newSource = patches.getValueOrDefault(oldSource, oldSource);
                final L label = entry.getValue();
                final boolean local = isOwner(newSource);
                targets.forEach(targetScope -> {
                    final S target = patches.getValueOrDefault(targetScope, targetScope);
                    if(local) {
                        scopes.__insert(newSource);
                        newScopeGraph.addEdge(newSource, label, target);
                    } else {
                        doAddEdge(self, newSource, label, target);
                        localScopeGraph.addEdge(newSource, label, target);
                    }
                    if(isOwner(target)) {
                        scopes.__insert(target);
                    }
                });
            });
            previousResult.localScopeGraph().getData().forEach((oldScope, datum) -> {
                final S newScope = patches.getValueOrDefault(oldScope, oldScope);
                if(isOwner(newScope)) {
                    newScopeGraph.setDatum(newScope, context.substituteScopes(datum, patches.asMap()));
                } else {
                    doSetDatum(newScope, datum);
                    localScopeGraph.setDatum(newScope, datum);
                }
            });

            scopeGraph.set(scopeGraph.get().addAll(newScopeGraph.freeze()));
            localScopeGraph.addAll(newScopeGraph);

            // initialize all scopes that are pending, and close all open labels.
            // these should be set by the now reused scopegraph.
            ownTokens().forEach(wf -> {
                // @formatter:off
                wf.visit(IWaitFor.cases(
                    initScope -> doInitShare(self, initScope.scope(), CapsuleUtil.immutableSet(), false),
                    closeScope -> {},
                    closeLabel -> doCloseLabel(self, closeLabel.scope(), closeLabel.label()),
                    query -> {},
                    confirm -> {},
                    complete -> {},
                    datum -> {},
                    match -> {},
                    result -> {},
                    typeCheckerState -> {},
                    differResult -> {},
                    activate -> {},
                    unitAdd -> {}
                ));
                // @formatter:on
            });

            // TODO: apply patches on queries?
            recordedQueries.addAll(previousResult.queries());
            stateTransitionTrace = TransitionTrace.RELEASED;

            // Cancel all futures waiting for activation
            whenActive.completeExceptionally(Release.instance);
            state = UnitState.RELEASED;
            confirmationResult.complete(Optional.of(patches));

            resume();
            tryFinish(); // FIXME needed?
        }
    }

    private boolean doRestart() {
        if(state == UnitState.INIT_TC || state == UnitState.UNKNOWN) {
            state = UnitState.ACTIVE;
            whenActive.complete(Unit.unit);
            confirmationResult.complete(Optional.empty());
            if(isDifferEnabled()) {
                final IDifferContext<S, L, D> context = differContext();
                final IDifferOps<S, L, D> differOps = differOps();
                if(previousResult != null) {
                    initDiffer(new ScopeGraphDiffer<>(context, new StaticDifferContext<>(previousResult.scopeGraph()),
                            differOps), this.rootScopes, previousResult.rootScopes());
                } else {
                    initDiffer(new AddingDiffer<>(context, differOps), Collections.emptyList(),
                            Collections.emptyList());
                }
                this.envDiffer = new EnvDiffer<>(differ, differOps());
            }
            resume();
            tryFinish(); // FIXME needed?
            return true;
        }
        return false;
    }

    private void doImplicitActivate() {
        // If invoked synchronously, do a implicit transition to ACTIVE
        // runIncremental may not be used anymore!
        if(state == UnitState.INIT_TC && doRestart()) {
            logger.debug("Performing implicit activation.");
            this.stateTransitionTrace = TransitionTrace.INITIALLY_STARTED;
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Deadlock handling
    ///////////////////////////////////////////////////////////////////////////

    @Override protected void handleDeadlock(java.util.Set<IProcess<S, L, D>> nodes) {
        if(nodes.size() == 1 && isWaitingFor(Activate.of(self, whenActive))) {
            assertInState(UnitState.UNKNOWN);
            doRelease(BiMap.Immutable.of());
        } else {
            super.handleDeadlock(nodes);
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Local result
    ///////////////////////////////////////////////////////////////////////////

    @Override protected Immutable<S, L, D> localScopeGraph() {
        return localScopeGraph.freeze();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Confirmation
    ///////////////////////////////////////////////////////////////////////////

    private interface IConfirmation<S, L, D> {
        IFuture<Optional<BiMap.Immutable<S>>> confirm(java.util.Set<IRecordedQuery<S, L, D>> queries);

        IFuture<Optional<BiMap.Immutable<S>>> confirm(IActorRef<? extends IUnit<S, L, D, ?>> sender,
                ScopePath<S, L> path, LabelWf<L> labelWF, DataWf<S, L, D> dataWF, boolean prevEnvEmpty);
    }

    private class TrivialConfirmation implements IConfirmation<S, L, D> {

        @Override public IFuture<Optional<BiMap.Immutable<S>>> confirm(java.util.Set<IRecordedQuery<S, L, D>> queries) {
            final List<IFuture<Boolean>> futures = new ArrayList<>();
            previousResult.queries().forEach(rq -> {
                final ICompletableFuture<Boolean> confirmationResult = new CompletableFuture<>();
                futures.add(confirmationResult);
                confirmationResult.thenAccept(res -> {
                    // Immediately restart when a query is invalidated
                    if(!res && doRestart()) {
                        stateTransitionTrace = TransitionTrace.RESTARTED;
                    }
                });
                final S scope = rq.scopePath().getTarget();
                getOwner(scope).thenAccept(owner -> {
                    self.async(owner)._match(scope).whenComplete((m, ex) -> {
                        if(ex != null) {
                            if(ex == Release.instance) {
                                confirmationResult.complete(true);
                            } else {
                                confirmationResult.completeExceptionally(ex);
                            }
                        } else if(!m.isPresent()) {
                            confirmationResult.complete(rq.result().isEmpty());
                        } else {
                            final ICompletableFuture<IQueryAnswer<S, L, D>> queryResult = new CompletableFuture<>();
                            final ScopePath<S, L> path = new ScopePath<>(m.get());
                            final Query<S, L, D> confirm = Query.of(self, path, rq.dataWf(), queryResult);
                            waitFor(confirm, owner);
                            // @formatter:off
                            self.async(owner)._query(path, rq.labelWf(), rq.dataWf(), rq.labelOrder(), rq.dataLeq())
                                .whenComplete(queryResult::complete);
                            queryResult.whenComplete((env, ex2) -> {
                                    granted(confirm, owner);
                                    resume();
                                    if(ex2 != null) {
                                        if(ex2 == Release.instance) {
                                            confirmationResult.complete(true);
                                        } else {
                                            confirmationResult.completeExceptionally(ex2);
                                        }
                                    } else {
                                        // Query is valid iff environments are equal
                                        // TODO: compare environments with scope patches.
                                        java.util.Set<ResolutionPath<S, L, D>> oldPaths = Sets.newHashSet(rq.result());
                                        java.util.Set<ResolutionPath<S, L, D>> newPaths = Sets.newHashSet(env.env());
                                        confirmationResult.complete(oldPaths.equals(newPaths));
                                    }
                                });
                            // @formatter:on
                            resume();
                        }
                    });
                });
            });

            return Futures.noneMatch(futures, p -> p.thenApply(v -> !v))
                    .thenApply(v -> v ? Optional.of(BiMap.Immutable.of()) : Optional.empty());
        }

        @Override public IFuture<Optional<BiMap.Immutable<S>>> confirm(IActorRef<? extends IUnit<S, L, D, ?>> sender,
                ScopePath<S, L> path, LabelWf<L> labelWF, DataWf<S, L, D> dataWF, boolean prevEnvEmpty) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

    }

    private abstract class BaseConfirmation implements IConfirmation<S, L, D> {

        private final SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>> DENY =
                SC.shortCircuit(Optional.empty());
        private final SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>> ACC_NO_PATCHES =
                SC.of(BiMap.Immutable.of());

        @Override public IFuture<Optional<BiMap.Immutable<S>>> confirm(java.util.Set<IRecordedQuery<S, L, D>> queries) {
            final List<IFuture<SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>>>> futures =
                    queries.stream().map(this::confirm).map(this::toSCFuture).collect(Collectors.toList());

            return AggregateFuture.ofShortCircuitable(this::merge, futures);
        }

        public abstract IFuture<Optional<BiMap.Immutable<S>>> confirm(IRecordedQuery<S, L, D> query);

        protected IFuture<Optional<BiMap.Immutable<S>>> confirmSingle(IRecordedQuery<S, L, D> query) {
            return confirm(self, query.scopePath(), query.labelWf(), query.dataWf(), query.result().isEmpty());
        }

        @Override public IFuture<Optional<BiMap.Immutable<S>>> confirm(IActorRef<? extends IUnit<S, L, D, ?>> sender,
                ScopePath<S, L> path, LabelWf<L> labelWF, DataWf<S, L, D> dataWF, boolean prevEnvEmpty) {
            final S scope = path.getTarget();
            return getOwner(scope).thenCompose(owner -> {
                if(!owner.equals(self)) {
                    final ICompletableFuture<Optional<BiMap.Immutable<S>>> result = new CompletableFuture<>();
                    final Confirm<S, L, D> confirm = Confirm.of(self, scope, labelWF, dataWF, result);
                    waitFor(confirm, owner);
                    self.async(owner)._confirm(path, labelWF, dataWF, prevEnvEmpty).whenComplete((v, ex) -> {
                        granted(confirm, owner);
                        resume();
                        if(ex != null && ex == Release.instance) {
                            result.complete(Optional.of(BiMap.Immutable.of()));
                        } else {
                            result.complete(v, ex);
                        }
                    });
                    return result;
                }
                return whenDifferActivated.thenCompose(__ -> {
                    return envDiffer.diff(path.getTarget(), path.scopeSet(), labelWF, dataWF).thenCompose(envDiff -> {
                        final ArrayList<IFuture<SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>>>> futures =
                                new ArrayList<>();
                        envDiff.diffPaths().forEach(diffPath -> {
                            // @formatter:off
                            futures.add(diffPath.getDatum().<IFuture<SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>>>>match(
                                addedEdge -> handleAddedEdge(sender, addedEdge),
                                removedEdge -> handleRemovedEdge(sender, removedEdge, prevEnvEmpty),
                                external -> handleExternal(sender, external),
                                diffTree -> { throw new IllegalStateException("Diff path cannot end in subtree"); }
                            ));
                            // @formatter:on
                        });

                        return AggregateFuture.<BiMap.Immutable<S>, Optional<BiMap.Immutable<S>>>ofShortCircuitable(
                                this::merge, futures);
                    });
                });
            });
        }

        protected abstract IFuture<SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>>>
                handleAddedEdge(IActorRef<? extends IUnit<S, L, D, ?>> sender, AddedEdge<S, L, D> addedEdge);

        protected abstract IFuture<SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>>>
                handleRemovedEdge(IActorRef<? extends IUnit<S, L, D, ?>> sender, RemovedEdge<S, L, D> removedEdge,
                        boolean prevEnvEnpty);

        protected abstract IFuture<SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>>>
                handleExternal(IActorRef<? extends IUnit<S, L, D, ?>> sender, External<S, L, D> external);

        protected SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>> deny() {
            return DENY;
        }

        protected SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>> accept() {
            return ACC_NO_PATCHES;
        }

        protected IFuture<SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>>> acceptFuture() {
            return CompletableFuture.completedFuture(ACC_NO_PATCHES);
        }

        protected IFuture<SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>>>
                accept(BiMap.Immutable<S> patches) {
            return CompletableFuture.completedFuture(SC.of(patches));
        }

        private Optional<BiMap.Immutable<S>> merge(List<BiMap.Immutable<S>> patchSets) {
            // Patch sets should be build from matches by scope differ, so just adding them is safe.
            return Optional.of(patchSets.stream().reduce(BiMap.Immutable.of(), BiMap.Immutable::putAll));
        }

        private SC<BiMap.Immutable<S>, Optional<BiMap.Immutable<S>>> toSC(Optional<BiMap.Immutable<S>> intermediate) {
            return intermediate.<SC<BiMap.Immutable<S>, Optional<BiMap.Immutable<S>>>>map(SC::of)
                    .orElse(SC.shortCircuit(Optional.empty()));
        }

        private IFuture<SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>>>
                toSCFuture(IFuture<Optional<BiMap.Immutable<S>>> intermediateFuture) {
            return intermediateFuture.thenApply(this::toSC);
        }

    }

    private class LazyConfirmation extends BaseConfirmation {

        @Override public IFuture<Optional<BiMap.Immutable<S>>> confirm(IRecordedQuery<S, L, D> query) {
            // First confirm root query
            return confirmSingle(query).thenCompose(res -> {
                return Futures.<BiMap.Immutable<S>>liftOptional(res.map(initialPatches -> {
                    // If that succeeds, confirm all transitive queries
                    return confirm(query.transitiveQueries()).thenCompose(res2 -> {
                        return Futures.<BiMap.Immutable<S>>liftOptional(res2.map(transitivePatches -> {
                            // If that succeeds, confirm all queries raised by predicates
                            return confirm(query.predicateQueries()).thenApply(res3 -> {
                                return res3.map(__ -> {
                                    // Do no include patches from predicate queries in eventual result, because
                                    // nested state should not leak to the outer state, and hence plays no role in confirmation.
                                    return initialPatches.putAll(transitivePatches);
                                });
                            });
                        }));
                    });
                }));
            });
        }

        @Override protected IFuture<SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>>>
                handleAddedEdge(IActorRef<? extends IUnit<S, L, D, ?>> sender, AddedEdge<S, L, D> addedEdge) {
            return doQuery(sender, new ScopePath<>(addedEdge.scope()), addedEdge.labelWf(), LabelOrder.none(),
                    addedEdge.dataWf(), DataLeq.none(), null, null)
                            .thenApply(ans -> ans.env().isEmpty() ? accept() : deny());
        }

        @Override protected IFuture<SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>>>
                handleRemovedEdge(IActorRef<? extends IUnit<S, L, D, ?>> sender, RemovedEdge<S, L, D> removedEdge,
                        boolean prevEnvEnpty) {
            if(prevEnvEnpty) {
                return acceptFuture();
            }
            return doQueryPrevious(previousResult.scopeGraph(), new ScopePath<>(removedEdge.scope()),
                    removedEdge.labelWf(), removedEdge.dataWf(), LabelOrder.none(), DataLeq.none())
                            .thenApply(env -> env.isEmpty() ? accept() : deny());
        }

        @Override protected IFuture<SC<? extends BiMap.Immutable<S>, ? extends Optional<BiMap.Immutable<S>>>>
                handleExternal(IActorRef<? extends IUnit<S, L, D, ?>> sender, External<S, L, D> external) {
            // External env diff validated by transitively recorded query
            return acceptFuture();
        }

    }
    ///////////////////////////////////////////////////////////////////////////
    // Assertions
    ///////////////////////////////////////////////////////////////////////////

    private void assertInState(UnitState s) {
        if(!state.equals(s)) {
            logger.error("Expected state {}, was {}", s, state);
            throw new IllegalStateException("Expected state " + s + ", was " + state);
        }
    }

    private void assertActive() {
        if(!state.active()) {
            logger.error("Expected active state, was {}", state);
            throw new IllegalStateException("Expected active state, but was " + state);
        }
    }

    private <Q> IFuture<Q> ifActive(IFuture<Q> result) {
        return result.compose((r, ex) -> {
            if(state != UnitState.DONE) {
                return CompletableFuture.completed(r, ex);
            } else {
                return CompletableFuture.noFuture();
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////
    // Incremental settings
    ///////////////////////////////////////////////////////////////////////////

    protected boolean isIncrementalEnabled() {
        return context.settings().incremental();
    }

    protected void assertIncrementalEnabled() {
        if(!isIncrementalEnabled()) {
            logger.error("Incremental analysis is not enabled");
            throw new IllegalStateException("Incremental analysis is not enabled");
        }
    }

    protected void assertPreviousResultProvided() {
        if(previousResult == null) {
            logger.error("Cannot confirm queries when no previous result is provided.");
            throw new IllegalStateException("Cannot confirm queries when no previous result is provided.");
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // toString
    ///////////////////////////////////////////////////////////////////////////

    @Override public String toString() {
        return "TypeCheckerUnit{" + self.id() + "}";
    }

}