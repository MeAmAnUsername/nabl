package mb.p_raffrayi.impl;

import java.util.List;

import org.metaborg.util.future.IFuture;
import org.metaborg.util.unit.Unit;

import mb.p_raffrayi.IScopeGraphLibrary;
import mb.p_raffrayi.ITypeChecker;
import mb.p_raffrayi.ITypeCheckerContext;
import mb.p_raffrayi.IUnitResult;

abstract class AbstractQueryTypeCheckerContext<S, L, D, R> implements ITypeCheckerContext<S, L, D> {

    @SuppressWarnings("unused") @Override public <Q> IFuture<IUnitResult<S, L, D, Q>> add(String id,
            ITypeChecker<S, L, D, Q> unitChecker, List<S> rootScopes, boolean changed) {
        throw new UnsupportedOperationException("Unsupported in query context.");
    }

    @SuppressWarnings("unused") @Override public IFuture<IUnitResult<S, L, D, Unit>> add(String id,
            IScopeGraphLibrary<S, L, D> library, List<S> rootScopes) {
        throw new UnsupportedOperationException("Unsupported in query context.");
    }

    @SuppressWarnings("unused") @Override public void initScope(S root, Iterable<L> labels, boolean sharing) {
        throw new UnsupportedOperationException("Unsupported in query context.");
    }

    @SuppressWarnings("unused") @Override public S freshScope(String baseName, Iterable<L> edgeLabels,
            boolean data, boolean sharing) {
        throw new UnsupportedOperationException("Unsupported in query context.");
    }

    @SuppressWarnings("unused") @Override public void shareLocal(S scope) {
        throw new UnsupportedOperationException("Unsupported in query context.");
    }

    @SuppressWarnings("unused") @Override public void setDatum(S scope, D datum) {
        throw new UnsupportedOperationException("Unsupported in query context.");
    }

    @SuppressWarnings("unused") @Override public void addEdge(S source, L label, S target) {
        throw new UnsupportedOperationException("Unsupported in query context.");
    }

    @SuppressWarnings("unused") @Override public void closeEdge(S source, L label) {
        throw new UnsupportedOperationException("Unsupported in query context.");
    }

    @SuppressWarnings("unused") @Override public void closeScope(S scope) {
        throw new UnsupportedOperationException("Unsupported in query context.");
    }

}