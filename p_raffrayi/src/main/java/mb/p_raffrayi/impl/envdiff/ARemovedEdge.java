package mb.p_raffrayi.impl.envdiff;

import org.immutables.value.Value;
import org.metaborg.util.collection.CapsuleUtil;
import org.metaborg.util.functions.Function1;

import io.usethesource.capsule.Set;
import mb.p_raffrayi.nameresolution.DataWf;
import mb.scopegraph.ecoop21.LabelWf;
import mb.scopegraph.oopsla20.terms.newPath.ResolutionPath;
import mb.scopegraph.oopsla20.terms.newPath.ScopePath;

@Value.Immutable
public abstract class ARemovedEdge<S, L, D> implements IEnvDiff<S, L, D> {

    @Value.Parameter public abstract S scope();

    @Value.Parameter public abstract LabelWf<L> labelWf();

    @Value.Parameter public abstract DataWf<S, L, D> dataWf();

    @Override public boolean isEmpty() {
        return false;
    }

    @Override @Value.Lazy public Set.Immutable<ResolutionPath<S, L, IEnvDiff<S, L, D>>> diffPaths() {
        return diffPaths(new ScopePath<S, L>(scope()));
    }

    @Override public Set.Immutable<ResolutionPath<S, L, IEnvDiff<S, L, D>>> diffPaths(ScopePath<S, L> prefix) {
        return CapsuleUtil.immutableSet(prefix.resolve(this));
    }

    @Override public <T> T match(Function1<AddedEdge<S, L, D>, T> onAddedEdge,
            Function1<RemovedEdge<S, L, D>, T> onRemovedEdge, Function1<External<S, L, D>, T> onExternal,
            Function1<DiffTree<S, L, D>, T> onDiffTree) {
        return onRemovedEdge.apply((RemovedEdge<S, L, D>) this);
    }
}
