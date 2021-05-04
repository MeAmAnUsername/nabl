package mb.scopegraph.pepm16;

import java.util.Collection;
import java.util.Map;

import org.metaborg.util.task.ICancel;
import org.metaborg.util.task.IProgress;

import mb.scopegraph.pepm16.esop15.CriticalEdge;
import mb.scopegraph.pepm16.path.IResolutionPath;


public interface INameResolution<S extends IScope, L extends ILabel, O extends IOccurrence> {

    java.util.Set<O> getResolvedRefs();

    Collection<IResolutionPath<S, L, O>> resolve(O ref, ICancel cancel, IProgress progress)
            throws CriticalEdgeException, StuckException, InterruptedException;

    Collection<O> decls(S scope) throws CriticalEdgeException, StuckException;

    Collection<O> refs(S scope) throws CriticalEdgeException, StuckException;

    Collection<O> visible(S scope, ICancel cancel, IProgress progress) throws CriticalEdgeException, StuckException, InterruptedException;

    Collection<O> reachable(S scope, ICancel cancel, IProgress progress) throws CriticalEdgeException, StuckException, InterruptedException;

    Collection<? extends Map.Entry<O, ? extends Collection<IResolutionPath<S, L, O>>>> resolutionEntries();

    default void update(Iterable<CriticalEdge> criticalEdges, ICancel cancel, IProgress progress) throws InterruptedException {
    }

}