package mb.nabl2.spoofax.primitives;

import java.util.List;
import java.util.Optional;

import org.spoofax.interpreter.core.InterpreterException;

import mb.nabl2.solver.ISolution;
import mb.nabl2.terms.ITerm;
import mb.scopegraph.pepm16.terms.Occurrence;

public class SG_get_decl_property extends AnalysisPrimitive {

    public SG_get_decl_property() {
        super(SG_get_decl_property.class.getSimpleName(), 1);
    }

    @Override public Optional<? extends ITerm> call(ISolution solution, ITerm term, List<ITerm> terms)
            throws InterpreterException {
        ITerm key = terms.get(0);
        return Occurrence.matcher().match(term, solution.unifier()).<ITerm>flatMap(decl -> {
            return solution.declProperties().getValue(decl, key).map(solution.unifier()::findRecursive);
        });
    }

}