package mb.nabl2.relations.terms;

import static mb.nabl2.terms.matching.TermMatch.M;

import mb.nabl2.relations.ARelationDescription.Reflexivity;
import mb.nabl2.relations.ARelationDescription.Symmetry;
import mb.nabl2.relations.ARelationDescription.Transitivity;
import mb.nabl2.terms.matching.TermMatch.IMatcher;

public class RelationOptions {

    public static IMatcher<Reflexivity> reflexivity() {
        // @formatter:off
        return M.<Reflexivity>cases(
            M.appl0("Reflexive", (t) -> Reflexivity.REFLEXIVE),
            M.appl0("Irreflexive", (t) -> Reflexivity.IRREFLEXIVE)
        );
        // @formatter:on
    }

    public static IMatcher<Symmetry> symmetry() {
        // @formatter:off
        return M.<Symmetry>cases(
            M.appl0("Symmetric", (t) -> Symmetry.SYMMETRIC),
            M.appl0("AntiSymmetric", (t) -> Symmetry.ANTI_SYMMETRIC)
        );
        // @formatter:on
    }

    public static IMatcher<Transitivity> transitivity() {
        // @formatter:off
        return M.<Transitivity>cases(
            M.appl0("Transitive", (t) -> Transitivity.TRANSITIVE),
            M.appl0("AntiTransitive", (t) -> Transitivity.ANTI_TRANSITIVE)
        );
        // @formatter:on
    }

}