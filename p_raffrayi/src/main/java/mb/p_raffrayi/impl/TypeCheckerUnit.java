package mb.p_raffrayi.impl;

import static com.google.common.collect.Streams.stream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.metaborg.util.Ref;
import org.metaborg.util.collection.CapsuleUtil;
import org.metaborg.util.collection.MultiSet;
import org.metaborg.util.functions.Function1;
import org.metaborg.util.functions.Function2;
import org.metaborg.util.future.CompletableFuture;
import org.metaborg.util.future.ICompletableFuture;
import org.metaborg.util.future.IFuture;
import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;
import org.metaborg.util.unit.Unit;

import com.google.common.collect.Sets;

import io.usethesource.capsule.Set;
import mb.p_raffrayi.IIncrementalTypeCheckerContext;
import mb.p_raffrayi.IResult;
import mb.p_raffrayi.IScopeGraphLibrary;
import mb.p_raffrayi.ITypeChecker;
import mb.p_raffrayi.IUnitResult;
import mb.p_raffrayi.APRaffrayiSettings.ConfirmationMode;
import mb.p_raffrayi.IUnitResult.TransitionTrace;
import mb.p_raffrayi.actors.IActor;
import mb.p_raffrayi.actors.IActorRef;
import mb.p_raffrayi.impl.confirm.ConfirmResult;
import mb.p_raffrayi.impl.confirm.IConfirmationContext;
import mb.p_raffrayi.impl.confirm.IConfirmationFactory;
import mb.p_raffrayi.impl.confirm.LazyConfirmation;
import mb.p_raffrayi.impl.confirm.TrivialConfirmation;
import mb.p_raffrayi.impl.diff.AddingDiffer;
import mb.p_raffrayi.impl.diff.IDifferContext;
import mb.p_raffrayi.impl.diff.IDifferOps;
import mb.p_raffrayi.impl.diff.IScopeGraphDiffer;
import mb.p_raffrayi.impl.diff.MatchingDiffer;
import mb.p_raffrayi.impl.diff.ScopeGraphDiffer;
import mb.p_raffrayi.impl.diff.StaticDifferContext;
import mb.p_raffrayi.impl.envdiff.EnvDiffer;
import mb.p_raffrayi.impl.envdiff.IEnvDiff;
import mb.p_raffrayi.impl.envdiff.IEnvDiffer;
import mb.p_raffrayi.impl.tokens.Activate;
import mb.p_raffrayi.impl.tokens.CloseLabel;
import mb.p_raffrayi.impl.tokens.CloseScope;
import mb.p_raffrayi.impl.tokens.Confirm;
import mb.p_raffrayi.impl.tokens.IWaitFor;
import mb.p_raffrayi.impl.tokens.InitScope;
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
import mb.scopegraph.oopsla20.terms.newPath.ScopePath;

class TypeCheckerUnit<S, L, D, R extends IResult<S, L, D>, T> extends AbstractUnit<S, L, D, R, T>
        implements IIncrementalTypeCheckerContext<S, L, D, R, T> {


    private static final ILogger logger = LoggerUtils.logger(TypeCheckerUnit.class);

    private final ITypeChecker<S, L, D, R, T> typeChecker;
    private final boolean changed;
    private final @Nullable IUnitResult<S, L, D, R, T> previousResult;

    private volatile UnitState state;

    private final BiMap.Transient<S> matchedBySharing = BiMap.Transient.of();

    private final Ref<IScopeGraph.Immutable<S, L, D>> localScopeGraph = new Ref<>(ScopeGraph.Immutable.of());

    private final Set.Transient<String> addedUnitIds = CapsuleUtil.transientSet();

    private final ICompletableFuture<Unit> whenActive = new CompletableFuture<>();
    private final ICompletableFuture<Unit> whenContextActivated = new CompletableFuture<>();

    private IEnvDiffer<S, L, D> envDiffer;

    private final IConfirmationFactory<S, L, D> confirmation;
    private final ICompletableFuture<Optional<BiMap.Immutable<S>>> confirmationResult = new CompletableFuture<>();

    TypeCheckerUnit(IActor<? extends IUnit<S, L, D, R, T>> self,
            @Nullable IActorRef<? extends IUnit<S, L, D, ?, ?>> parent, IUnitContext<S, L, D> context,
            ITypeChecker<S, L, D, R, T> unitChecker, Iterable<L> edgeLabels, boolean inputChanged,
            IUnitResult<S, L, D, R, T> previousResult) {
        super(self, parent, context, edgeLabels);
        this.typeChecker = unitChecker;
        this.changed = inputChanged;
        this.previousResult = previousResult;
        this.state = UnitState.INIT_UNIT;
        switch(context.settings().confirmationMode()) {
            case TRIVIAL:
                confirmation = TrivialConfirmation.factory();
                break;
            case SIMPLE_ENVIRONMENT:
                confirmation = LazyConfirmation.factory();
                break;
            default:
                throw new IllegalStateException("Unknown confirmation mode: " + context.settings().confirmationMode());
        }
    }

    TypeCheckerUnit(IActor<? extends IUnit<S, L, D, R, T>> self,
            @Nullable IActorRef<? extends IUnit<S, L, D, ?, ?>> parent, IUnitContext<S, L, D> context,
            ITypeChecker<S, L, D, R, T> unitChecker, Iterable<L> edgeLabels) {
        this(self, parent, context, unitChecker, edgeLabels, true, null);
    }


    @Override protected IFuture<D> getExternalDatum(D datum) {
        return typeChecker.getExternalDatum(datum);
    }

    @Override protected D getPreviousDatum(D datum) {
        assertPreviousResultProvided();
        return previousResult.analysis().getExternalRepresentation(datum);
    }

    ///////////////////////////////////////////////////////////////////////////
    // IBroker2UnitProtocol interface, called by IBroker implementations
    ///////////////////////////////////////////////////////////////////////////

    @Override public IFuture<IUnitResult<S, L, D, R, T>> _start(List<S> rootScopes) {
        assertInState(UnitState.INIT_UNIT);
        resume();

        doStart(rootScopes);
        state = UnitState.INIT_TC;
        final IFuture<R> result = this.typeChecker.run(this, rootScopes).whenComplete((r, ex) -> {
            if(state == UnitState.INIT_TC) {
                stateTransitionTrace = TransitionTrace.INITIALLY_STARTED;
            }
            if(inLocalPhase()) {
                doCapture();
            }
            state = UnitState.DONE;
        });

        // Start phantom units for all units that have not yet been restarted
        if(previousResult != null) {
            for(String removedId : Sets.difference(previousResult.subUnitResults().keySet(), addedUnitIds)) {
                final IUnitResult<S, L, D, ?, ?> subResult = previousResult.subUnitResults().get(removedId);
                this.<IResult.Empty<S, L, D>, Unit>doAddSubUnit(removedId,
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

    @Override public IFuture<ConfirmResult<S>> _confirm(ScopePath<S, L> path, LabelWf<L> labelWF,
            DataWf<S, L, D> dataWF, boolean prevEnvEmpty) {
        assertConfirmationEnabled();
        stats.incomingConfirmations++;
        final IActorRef<? extends IUnit<S, L, D, ?, ?>> sender = self.sender(TYPE);
        final ICompletableFuture<ConfirmResult<S>> result = new CompletableFuture<>();
        whenActive.whenComplete((__, ex) -> {
            if(ex != null && ex != Release.instance) {
                result.completeExceptionally(ex);
            } else {
                confirmation.getConfirmation(new ConfirmationContext(sender))
                        .confirm(path, labelWF, dataWF, prevEnvEmpty).whenComplete(result::complete);
            }
        });
        return result;
    }

    @Override public IFuture<Env<S, L, D>> _queryPrevious(ScopePath<S, L> path, LabelWf<L> labelWF,
            DataWf<S, L, D> dataWF, LabelOrder<L> labelOrder, DataLeq<S, L, D> dataEquiv) {
        assertPreviousResultProvided();
        return doQueryPrevious(self.sender(TYPE), previousResult.scopeGraph(), path, labelWF, dataWF, labelOrder,
                dataEquiv);
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

    @Override public <Q extends IResult<S, L, D>, U> IFuture<IUnitResult<S, L, D, Q, U>> add(String id, ITypeChecker<S, L, D, Q, U> unitChecker,
            List<S> rootScopes, boolean changed) {
        assertActive();

        // No previous result for subunit
        if(this.previousResult == null || !this.previousResult.subUnitResults().containsKey(id)) {
            this.addedUnitIds.__insert(id);

            return ifActive(whenContextActive(this.<Q, U>doAddSubUnit(id, (subself, subcontext) -> {
                return new TypeCheckerUnit<>(subself, self, subcontext, unitChecker, edgeLabels);
            }, rootScopes, false)._2()));
        }

        @SuppressWarnings("unchecked") final IUnitResult<S, L, D, Q, U> subUnitPreviousResult =
                (IUnitResult<S, L, D, Q, U>) this.previousResult.subUnitResults().get(id);


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
        final IFuture<IUnitResult<S, L, D, Q, U>> result = this.<Q, U>doAddSubUnit(id, (subself, subcontext) -> {
            return new TypeCheckerUnit<S, L, D, Q, U>(subself, self, subcontext, unitChecker, edgeLabels, changed,
                    subUnitPreviousResult);
        }, rootScopes, false)._2();

        return ifActive(whenContextActive(result));
    }

    @Override public IFuture<IUnitResult<S, L, D, IResult.Empty<S, L, D>, Unit>> add(String id, IScopeGraphLibrary<S, L, D> library,
            List<S> rootScopes) {
        assertActive();

        final IFuture<IUnitResult<S, L, D, IResult.Empty<S, L, D>, Unit>> result =
                this.<IResult.Empty<S, L, D>, Unit>doAddSubUnit(id, (subself, subcontext) -> {
                    return new ScopeGraphLibraryUnit<>(subself, self, subcontext, edgeLabels, library);
                }, rootScopes, true)._2();

        return ifActive(whenContextActive(result));
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
        localScopeGraph.set(localScopeGraph.get().setDatum(scope, datum));
    }

    @Override public void addEdge(S source, L label, S target) {
        assertActive();
        doImplicitActivate();

        doAddEdge(self, source, label, target);
        localScopeGraph.set(localScopeGraph.get().addEdge(source, label, target));
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
        return ifActive(whenContextActive(ret)).thenApply(ans -> {
            return CapsuleUtil.toSet(ans.env());
        });
    }

    @Override public <Q> IFuture<R> runIncremental(
            Function1<Optional<T>, IFuture<Q>> runLocalTypeChecker, Function1<R, Q> extractLocal,
            Function2<Q, BiMap.Immutable<S>, Q> patch, Function2<Q, Throwable, IFuture<R>> combine) {
        assertInState(UnitState.INIT_TC);
        state = UnitState.UNKNOWN;
        if(!isIncrementalEnabled() || changed) {
            logger.debug("Unit changed or no previous result was available.");
            stateTransitionTrace = TransitionTrace.INITIALLY_STARTED;
            doRestart();
            return runLocalTypeChecker.apply(Optional.empty()).compose(combine::apply);
        }

        // Invariant: added units are marked as changed.
        // Therefore, if unit is not changed, previousResult cannot be null.
        assertPreviousResultProvided();

        if(previousResult.localState() != null) {
            doRestore(previousResult.localState());
        }

        doConfirmQueries();

        return confirmationResult.thenCompose(patches -> {
            if(patches.isPresent()) {
                final Q previousLocalResult = extractLocal.apply(previousResult.analysis());
                return combine.apply(patch.apply(previousLocalResult, patches.get()), null);
            } else {
                final Optional<T> initialState =
                        Optional.ofNullable(previousResult.localState()).map(StateCapture::typeCheckerState);
                return runLocalTypeChecker.apply(initialState).compose(combine::apply);
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////
    // Implementation -- confirmation, restart and reuse
    ///////////////////////////////////////////////////////////////////////////

    private void doConfirmQueries() {
        logger.debug("Confirming queries from previous result.");
        assertInState(UnitState.UNKNOWN);
        assertIncrementalEnabled();
        assertPreviousResultProvided();
        resume();

        if(previousResult.queries().isEmpty()) {
            logger.debug("Releasing - no queries in previous result.");
            doRelease(BiMap.Immutable.of());
            return;
        }

        confirmation.getConfirmation(new ConfirmationContext(self)).confirm(previousResult.queries())
                .whenComplete((r, ex) -> {
                    if(ex == Release.instance) {
                        logger.debug("Confirmation received release.");
                        // Do nothing, unit is already released by deadlock resolution.
                    } else if(ex != null) {
                        logger.error("Failure in confirmation.", ex);
                        failures.add(ex);
                    } else {
                        r.visit(() -> {
                            // No confirmation, hence restart
                            logger.debug("Query confirmation denied - restarting.");
                            if(doRestart()) {
                                stateTransitionTrace = TransitionTrace.RESTARTED;
                            }
                        }, patches -> {
                            logger.debug("Queries confirmed - releasing.");
                            this.doRelease(patches);
                        });
                    }
                });
    }

    private void doRelease(BiMap.Immutable<S> patches) {
        assertIncrementalEnabled();
        if(state == UnitState.UNKNOWN) {
            logger.debug("{} releasing.", this);
            logger.trace("Patches: {}.", patches);
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
            if(isConfirmationEnabled()) {
                this.envDiffer = new EnvDiffer<>(differ, differOps());
            }

            logger.debug("Rebuilding scope graph.");
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
                        localScopeGraph.set(localScopeGraph.get().addEdge(newSource, label, target));
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
                    localScopeGraph.set(localScopeGraph.get().setDatum(newScope, datum));
                }
            });

            scopeGraph.set(scopeGraph.get().addAll(newScopeGraph.freeze()));
            localScopeGraph.set(localScopeGraph.get().addAll(newScopeGraph));

            // initialize all scopes that are pending, and close all open labels.
            // these should be set by the now reused scopegraph.
            logger.debug("Close pending tokens.");
            ownTokens().forEach(wf -> {
                // @formatter:off
                wf.visit(IWaitFor.cases(
                    initScope -> doInitShare(self, initScope.scope(), CapsuleUtil.immutableSet(), false),
                    closeScope -> {},
                    closeLabel -> doCloseLabel(self, closeLabel.scope(), closeLabel.label()),
                    query -> {},
                    pQuery -> {},
                    confirm -> {},
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
            self.complete(whenContextActivated, Unit.unit, null);
            self.complete(confirmationResult, Optional.of(patches), null);
            state = UnitState.RELEASED;

            resume();
            tryFinish(); // FIXME needed?
            logger.debug("{} released.", this);
        }
    }

    private boolean doRestart() {
        assertIncrementalEnabled();
        if(state == UnitState.INIT_TC || state == UnitState.UNKNOWN) {
            logger.debug("{} restarting.", this);
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
                if(isConfirmationEnabled()) {
                    this.envDiffer = new EnvDiffer<>(differ, differOps());
                }
            }
            resume();
            tryFinish(); // FIXME needed?
            logger.debug("{} restarted.", this);
            return true;
        }
        return false;
    }

    private void doImplicitActivate() {
        // If invoked synchronously, do a implicit transition to ACTIVE
        // runIncremental may not be used anymore!
        if(state == UnitState.INIT_TC && doRestart()) {
            logger.debug("{} implicitly activated.");
            this.stateTransitionTrace = TransitionTrace.INITIALLY_STARTED;
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Deadlock
    ///////////////////////////////////////////////////////////////////////////

    @Override public java.util.Set<IProcess<S, L, D>> dependentSet() {
        if(state.active() && inLocalPhase()) {
            return Collections.singleton(process);
        }
        return super.dependentSet();
    }

    @Override protected void handleDeadlock(java.util.Set<IProcess<S, L, D>> nodes) {
        if(nodes.size() == 1 && isWaitingFor(Activate.of(self, whenActive))) {
            assertInState(UnitState.UNKNOWN);
            logger.debug("{} self-deadlocked before activation, releasing", this);
            doRelease(BiMap.Immutable.of());
        } else if(state.active() && inLocalPhase()) {
            doCapture();
            self.complete(whenContextActivated, Unit.unit, null);
            resume();
        } else {
            super.handleDeadlock(nodes);
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Local result
    ///////////////////////////////////////////////////////////////////////////

    @Override protected Immutable<S, L, D> localScopeGraph() {
        return localScopeGraph.get();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Confirmation
    ///////////////////////////////////////////////////////////////////////////

    private class ConfirmationContext implements IConfirmationContext<S, L, D> {

        private final IActorRef<? extends IUnit<S, L, D, ?, ?>> sender;

        public ConfirmationContext(IActorRef<? extends IUnit<S, L, D, ?, ?>> sender) {
            this.sender = sender;
        }

        @Override public IFuture<IQueryAnswer<S, L, D>> query(ScopePath<S, L> scopePath, LabelWf<L> labelWf,
                LabelOrder<L> labelOrder, DataWf<S, L, D> dataWf, DataLeq<S, L, D> dataEquiv) {
            logger.debug("query from env differ.");
            return doQuery(sender, scopePath, labelWf, labelOrder, dataWf, dataEquiv, null, null);
        }

        @Override public IFuture<Env<S, L, D>> queryPrevious(ScopePath<S, L> scopePath, LabelWf<L> labelWf,
                DataWf<S, L, D> dataWf, LabelOrder<L> labelOrder, DataLeq<S, L, D> dataEquiv) {
            assertPreviousResultProvided();
            logger.debug("previous query from env differ.");
            return doQueryPrevious(sender, previousResult.scopeGraph(), scopePath, labelWf, dataWf, labelOrder,
                    dataEquiv);
        }

        @Override public IFuture<Optional<ConfirmResult<S>>> externalConfirm(ScopePath<S, L> path, LabelWf<L> labelWF,
                DataWf<S, L, D> dataWF, boolean prevEnvEmpty) {
            logger.debug("external confirm");
            final S scope = path.getTarget();
            return getOwner(path.getTarget()).thenCompose(owner -> {
                if(owner.equals(self)) {
                    logger.debug("local confirm");
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                logger.debug("external confirm");
                final ICompletableFuture<ConfirmResult<S>> result = new CompletableFuture<>();
                final Confirm<S, L, D> confirm = Confirm.of(self, scope, labelWF, dataWF, result);
                waitFor(confirm, owner);
                self.async(owner)._confirm(path, labelWF, dataWF, prevEnvEmpty).whenComplete((v, ex) -> {
                    logger.debug("rec external confirm");
                    granted(confirm, owner);
                    resume();
                    if(ex != null && ex == Release.instance) {
                        logger.debug("got release, confirming");
                        result.complete(ConfirmResult.confirm(BiMap.Immutable.of()));
                    } else if(ex != null) {
                        result.completeExceptionally(ex);
                    } else {
                        logger.trace("confirm: {}.", v);
                        result.complete(v);
                    }
                });
                return result.thenApply(Optional::of);
            });
        }

        @Override public IFuture<IEnvDiff<S, L, D>> envDiff(ScopePath<S, L> path, LabelWf<L> labelWf,
                DataWf<S, L, D> dataWf) {
            assertConfirmationEnabled();
            logger.debug("local env diff");
            return whenDifferActivated
                    .thenCompose(__ -> envDiffer.diff(path.getTarget(), path.scopeSet(), labelWf, dataWf));
        }

        @Override public IFuture<Optional<S>> match(S scope) {
            return getOwner(scope).thenCompose(owner -> self.async(owner)._match(scope));
        }

    }

    ///////////////////////////////////////////////////////////////////////////
    // Scopegraph Capture
    ///////////////////////////////////////////////////////////////////////////

    protected boolean inLocalPhase() {
        return !whenContextActivated.isDone();
    }

    protected <Q> IFuture<Q> whenContextActive(IFuture<Q> future) {
        if(!inLocalPhase()) {
            return future;
        }
        return whenContextActivated.thenCompose(__ -> future);
    }

    private void doCapture() {
        final T snapshot;
        if((snapshot = typeChecker.snapshot()) != null) {
            final StateCapture.Builder<S, L, D, T> builder = StateCapture.builder();
            final Set.Immutable<S> scopes = CapsuleUtil.toSet(this.scopes);
            builder.scopes(scopes);
            builder.scopeGraph(localScopeGraph.get());
            localScopeGraph.set(ScopeGraph.Immutable.of());

            final Set.Transient<EdgeOrData<L>> _edgeLabels = CapsuleUtil.transientSet(EdgeOrData.data());
            this.edgeLabels.forEach(lbl -> _edgeLabels.__insert(EdgeOrData.edge(lbl)));
            final Set.Immutable<EdgeOrData<L>> edgeLabels = _edgeLabels.freeze();

            for(S scope : scopes) {
                final int initCount = countWaitingFor(InitScope.of(self, scope), self);
                for(int i = 0; i < initCount; i++) {
                    builder.addUnInitializedScopes(scope);
                }

                final int closeCount = countWaitingFor(CloseScope.of(self, scope), self);
                for(int i = 0; i < closeCount; i++) {
                    builder.addOpenScopes(scope);
                }

                for(EdgeOrData<L> label : edgeLabels) {
                    final int closeLabelCount = countWaitingFor(CloseLabel.of(self, scope, label), self);
                    for(int i = 0; i < closeLabelCount; i++) {
                        builder.putOpenEdges(scope, label);
                    }
                }
            }

            final MultiSet.Transient<String> counters = MultiSet.Transient.of();
            counters.addAll(this.scopeNameCounters);
            builder.scopeNameCounters(scopeNameCounters.freeze());
            builder.typeCheckerState(snapshot);

            localCapture(builder.build());
        }
    }

    private void doRestore(StateCapture<S, L, D, T> snapshot) {
        // TODO: assert only root scopes in this.scopes?
        this.scopes.__insertAll(snapshot.scopes());

        // TODO assert empty
        this.scopeNameCounters = snapshot.scopeNameCounters().melt();

        for(S scope : snapshot.unInitializedScopes()) {
            waitFor(InitScope.of(self, scope), self);
        }

        for(S scope : snapshot.openScopes()) {
            waitFor(CloseScope.of(self, scope), self);
        }

        for(Map.Entry<S, EdgeOrData<L>> openEdge : snapshot.openEdges().entries()) {
            waitFor(CloseLabel.of(self, openEdge.getKey(), openEdge.getValue()), self);
        }

        // TODO: assert empty?
        // TODO: patch root scopes?
        this.scopeGraph.set(snapshot.scopeGraph());

        final List<EdgeOrData<L>> edges = edgeLabels.stream().map(EdgeOrData::edge).collect(Collectors.toList());
        final Iterator<S> pScopeIterator = previousResult.rootScopes().iterator();
        for(S currentScope : rootScopes) {
            doInitShare(self, currentScope, edges, false);
            final S previousScope = pScopeIterator.next();
            for(L label : edgeLabels) {
                for(S target: snapshot.scopeGraph().getEdges(previousScope, label)) {
                    self.async(parent)._addEdge(currentScope, label, target);
                }
                final CloseLabel<S, L, D> closeEdge = CloseLabel.of(self, currentScope, EdgeOrData.edge(label));
                if(!snapshot.isOpen(previousScope, EdgeOrData.edge(label)) && isWaitingFor(closeEdge, self)) {
                    doCloseLabel(self, currentScope, EdgeOrData.edge(label));
                }
            }
        }

        // TODO: patch capture with new root scopes?
        localCapture(snapshot);
        self.complete(whenContextActivated, Unit.unit, null);
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

    protected boolean isConfirmationEnabled() {
        return context.settings().confirmationMode() != ConfirmationMode.TRIVIAL;
    }

    protected void assertConfirmationEnabled() {
        if(!isConfirmationEnabled()) {
            logger.error("Environment differ not enabled.");
            throw new IllegalStateException("Environment differ not enabled.");
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // toString
    ///////////////////////////////////////////////////////////////////////////

    @Override public String toString() {
        return "TypeCheckerUnit{" + self.id() + "}";
    }

}