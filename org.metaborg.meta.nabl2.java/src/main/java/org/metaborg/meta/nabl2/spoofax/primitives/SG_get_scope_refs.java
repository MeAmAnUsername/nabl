package org.metaborg.meta.nabl2.spoofax.primitives;

import java.util.Optional;

import org.metaborg.meta.nabl2.scopegraph.terms.Scope;
import org.metaborg.meta.nabl2.spoofax.analysis.IScopeGraphContext;
import org.metaborg.meta.nabl2.stratego.TermIndex;
import org.metaborg.meta.nabl2.terms.ITerm;
import org.metaborg.meta.nabl2.terms.generic.TB;
import org.spoofax.interpreter.core.InterpreterException;

public class SG_get_scope_refs extends AnalysisPrimitive {

    public SG_get_scope_refs() {
        super(SG_get_scope_refs.class.getSimpleName());
    }

    @Override public Optional<? extends ITerm> call(IScopeGraphContext<?> context, TermIndex index, ITerm term)
            throws InterpreterException {
        return Scope.matcher().match(term).<ITerm>flatMap(scope -> {
            return context.unit(index.getResource()).solution().<ITerm>map(s -> {
                return TB.newList(s.getScopeGraph().getRefs().inverse().get(scope));
            });
        });
    }

}