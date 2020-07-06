package mb.nabl2.constraints.relations;

import static mb.nabl2.terms.build.TermBuild.B;
import static mb.nabl2.terms.matching.TermMatch.M;

import org.metaborg.util.functions.Function1;

import mb.nabl2.constraints.messages.MessageInfo;
import mb.nabl2.relations.terms.FunctionName;
import mb.nabl2.relations.terms.RelationName;
import mb.nabl2.terms.ITerm;
import mb.nabl2.terms.matching.TermMatch.IMatcher;
import mb.nabl2.terms.substitution.ISubstitution;

public final class RelationConstraints {

    private static final String C_BUILD_REL = "CBuildRel";
    private static final String C_CHECK_REL = "CCheckRel";
    private static final String C_EVAL = "CEval";

    public static IMatcher<IRelationConstraint> matcher() {
        return M.<IRelationConstraint>cases(
        // @formatter:off
            M.appl4(C_BUILD_REL, M.term(), RelationName.matcher(), M.term(), MessageInfo.matcher(), (c, term1, rel, term2, origin) -> {
                return CBuildRelation.of(term1, rel, term2, origin);
            }),
            M.appl4(C_CHECK_REL, M.term(), RelationName.matcher(), M.term(), MessageInfo.matcher(), (c, term1, rel, term2, origin) -> {
                return CCheckRelation.of(term1, rel, term2, origin);
            }),
            M.appl4(C_EVAL, M.term(), FunctionName.matcher(), M.term(), MessageInfo.matcher(), (c, result, fun, term, origin) -> {
                return CEvalFunction.of(result, fun, term, origin);
            })
            // @formatter:on
        );
    }

    public static ITerm build(IRelationConstraint constraint) {
        return constraint.match(IRelationConstraint.Cases.<ITerm>of(
        // @formatter:off
            build -> B.newAppl(C_BUILD_REL, build.getLeft(), build.getRelation(), build.getRight(),
                                MessageInfo.build(build.getMessageInfo())),
            check -> B.newAppl(C_CHECK_REL, check.getLeft(), check.getRelation(), check.getRight(),
                                MessageInfo.build(check.getMessageInfo())),
            eval -> B.newAppl(C_EVAL, eval.getResult(), eval.getFunction(), eval.getTerm(),
                               MessageInfo.build(eval.getMessageInfo()))
            // @formatter:on
        ));
    }

    public static IRelationConstraint substitute(IRelationConstraint constraint, ISubstitution.Immutable subst) {
        // @formatter:off
        return constraint.match(IRelationConstraint.Cases.<IRelationConstraint>of(
            build -> CBuildRelation.of(
                        subst.apply(build.getLeft()),
                        build.getRelation(),
                        subst.apply(build.getRight()),
                        build.getMessageInfo().apply(subst::apply)),
            check -> CCheckRelation.of(
                        subst.apply(check.getLeft()),
                        check.getRelation(),
                        subst.apply(check.getRight()),
                        check.getMessageInfo().apply(subst::apply)),
            eval -> CEvalFunction.of(
                        subst.apply(eval.getResult()),
                        eval.getFunction(),
                        subst.apply(eval.getTerm()),
                        eval.getMessageInfo().apply(subst::apply))
        ));
        // @formatter:on
    }

    public static IRelationConstraint transform(IRelationConstraint constraint, Function1<ITerm, ITerm> map) {
        // @formatter:off
        return constraint.match(IRelationConstraint.Cases.<IRelationConstraint>of(
            build -> CBuildRelation.of(
                        map.apply(build.getLeft()),
                        build.getRelation(),
                        map.apply(build.getRight()),
                        build.getMessageInfo().apply(map::apply)),
            check -> CCheckRelation.of(
                        map.apply(check.getLeft()),
                        check.getRelation(),
                        map.apply(check.getRight()),
                        check.getMessageInfo().apply(map::apply)),
            eval -> CEvalFunction.of(
                        map.apply(eval.getResult()),
                        eval.getFunction(),
                        map.apply(eval.getTerm()),
                        eval.getMessageInfo().apply(map::apply))
        ));
        // @formatter:on
    }

}