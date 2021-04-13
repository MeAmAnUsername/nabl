package mb.statix.generator.strategy;

import org.metaborg.util.functions.Predicate2;

import mb.nabl2.terms.ITerm;
import mb.nabl2.terms.unification.ud.IUniDisunifier;
import mb.scopegraph.oopsla20.reference.EdgeOrData;
import mb.scopegraph.oopsla20.reference.LabelOrder;
import mb.scopegraph.oopsla20.reference.LabelWF;
import mb.scopegraph.oopsla20.reference.ResolutionException;
import mb.statix.constraints.CEqual;
import mb.statix.constraints.CResolveQuery;
import mb.statix.generator.FocusedSearchState;
import mb.statix.generator.SearchContext;
import mb.statix.generator.SearchStrategy;
import mb.statix.generator.nodes.SearchNode;
import mb.statix.generator.nodes.SearchNodes;
import mb.statix.generator.scopegraph.DataWF;
import mb.statix.generator.scopegraph.NameResolution;
import mb.statix.scopegraph.Scope;
import mb.statix.solver.IState;
import mb.statix.solver.completeness.ICompleteness;
import mb.statix.solver.query.RegExpLabelWF;
import mb.statix.solver.query.RelationLabelOrder;

public final class CanResolve
        extends SearchStrategy<FocusedSearchState<CResolveQuery>, FocusedSearchState<CResolveQuery>> {


    @Override protected SearchNodes<FocusedSearchState<CResolveQuery>> doApply(SearchContext ctx,
            SearchNode<FocusedSearchState<CResolveQuery>> node) {
        FocusedSearchState<CResolveQuery> input = node.output();
        final IState.Immutable state = input.state();
        final IUniDisunifier unifier = state.unifier();
        final CResolveQuery query = input.focus();

        final Scope scope = Scope.matcher().match(query.scopeTerm(), unifier).orElse(null);
        if(scope == null) {
            return SearchNodes.failure(node, this.toString() + "[no scope]");
        }

        final Boolean isAlways;
        try {
            isAlways = query.min().getDataEquiv().isAlways().orElse(null);
        } catch(InterruptedException e) {
            throw new RuntimeException(e);
        }
        if(isAlways == null) {
            return SearchNodes.failure(node, this.toString() + "[cannot decide data equivalence]");
        }

        final ICompleteness.Immutable completeness = input.completeness();
        final LabelWF<ITerm> labelWF = RegExpLabelWF.of(query.filter().getLabelWF());
        final LabelOrder<ITerm> labelOrd = new RelationLabelOrder(query.min().getLabelOrder());
        final DataWF<ITerm, CEqual> dataWF = new ResolveDataWF(state, completeness, query.filter().getDataWF(), query);
        final Predicate2<Scope, EdgeOrData<ITerm>> isComplete =
                (s, l) -> completeness.isComplete(s, l, state.unifier());

        // @formatter:off
        final NameResolution<Scope, ITerm, ITerm, CEqual> nameResolution = new NameResolution<>(
                ctx.spec(),
                state.scopeGraph(),
                ctx.spec().allLabels(),
                labelWF, labelOrd, 
                dataWF, isAlways, isComplete);
        // @formatter:on

        try {
            nameResolution.resolve(scope, () -> false);
        } catch(ResolutionException e) {
            return SearchNodes.failure(node, this.toString() + "[cannot resolve]");
        } catch(InterruptedException e) {
            throw new RuntimeException(e);
        }

        return SearchNodes.of(node, this::toString, node);
    }

    @Override public String toString() {
        return "can-resolve";
    }

}