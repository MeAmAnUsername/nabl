package mb.nabl2.scopegraph.esop.bottomup;

import java.util.stream.Stream;

import org.metaborg.util.functions.Function1;
import org.metaborg.util.functions.Predicate2;

import mb.nabl2.scopegraph.ILabel;
import mb.nabl2.scopegraph.IOccurrence;
import mb.nabl2.scopegraph.IScope;
import mb.nabl2.scopegraph.path.IDeclPath;
import mb.nabl2.util.Tuple2;

public class BUChanges<S extends IScope, L extends ILabel, O extends IOccurrence, P extends IDeclPath<S, L, O>> {

    private final BUPathSet.Immutable<S, L, O, P> addedPaths;
    private final BUPathSet.Immutable<S, L, O, P> removedPaths;

    BUChanges(BUPathSet.Immutable<S, L, O, P> addedPaths, BUPathSet.Immutable<S, L, O, P> removedPaths) {
        this.addedPaths = addedPaths;
        this.removedPaths = removedPaths;
    }

    public boolean isEmpty() {
        return addedPaths.isEmpty() && removedPaths.isEmpty();
    }

    public BUPathSet.Immutable<S, L, O, P> addedPaths() {
        return addedPaths;
    }

    public BUPathSet.Immutable<S, L, O, P> removedPaths() {
        return removedPaths;
    }

    public <Q extends IDeclPath<S, L, O>> BUChanges<S, L, O, Q>
            flatMap(Function1<P, Stream<Tuple2<BUPathKey<L>, Q>>> pathMapper) {
        final BUPathSet.Transient<S, L, O, Q> mappedAddedPaths = BUPathSet.Transient.of();
        for(P ap : addedPaths.paths()) {
            pathMapper.apply(ap).forEach(e -> mappedAddedPaths.add(e._1(), e._2()));
        }
        final BUPathSet.Transient<S, L, O, Q> mappedRemovedPaths = BUPathSet.Transient.of();
        for(P rp : removedPaths.paths()) {
            pathMapper.apply(rp).forEach(e -> mappedRemovedPaths.add(e._1(), e._2()));
        }
        return new BUChanges<>(mappedAddedPaths.freeze(), mappedRemovedPaths.freeze());
    }

    public BUChanges<S, L, O, P> filter(Predicate2<BUPathKey<L>, P> pathFilter) {
        return new BUChanges<>(addedPaths.filter(pathFilter), removedPaths.filter(pathFilter));
    }


    public static <S extends IScope, L extends ILabel, O extends IOccurrence, P extends IDeclPath<S, L, O>>
            BUChanges<S, L, O, P> of(BUEnvKey<S, L> origin) {
        return new BUChanges<>(BUPathSet.Immutable.of(), BUPathSet.Immutable.of());
    }

    public static <S extends IScope, L extends ILabel, O extends IOccurrence, P extends IDeclPath<S, L, O>>
            BUChanges<S, L, O, P> ofPaths(BUEnvKey<S, L> origin, BUPathSet.Immutable<S, L, O, P> paths) {
        return new BUChanges<>(paths, BUPathSet.Immutable.of());
    }

}