package org.metaborg.meta.nabl2.spoofax.primitives;

import static org.metaborg.meta.nabl2.terms.build.TermBuild.B;

import java.util.List;
import java.util.Optional;

import org.metaborg.meta.nabl2.spoofax.analysis.IScopeGraphUnit;
import org.metaborg.meta.nabl2.terms.ITerm;
import org.spoofax.interpreter.core.InterpreterException;

public class SG_get_all_refs extends AnalysisPrimitive {

    public SG_get_all_refs() {
        super(SG_get_all_refs.class.getSimpleName());
    }

    @Override public Optional<? extends ITerm> call(IScopeGraphUnit unit, ITerm term, List<ITerm> terms) throws InterpreterException {
        return unit.solution().<ITerm>map(s -> {
            return B.newList(s.scopeGraph().getAllRefs());
        });
    }

}