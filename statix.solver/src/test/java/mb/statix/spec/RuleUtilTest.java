package mb.statix.spec;

import static mb.nabl2.terms.build.TermBuild.B;
import static mb.nabl2.terms.matching.TermPattern.P;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;

import com.google.common.collect.ImmutableCollection;

import io.usethesource.capsule.Set;
import mb.nabl2.terms.IStringTerm;
import mb.nabl2.terms.ITermVar;
import mb.nabl2.terms.matching.Pattern;
import mb.statix.constraints.CConj;
import mb.statix.constraints.CEqual;
import mb.statix.constraints.CExists;
import mb.statix.constraints.CTrue;
import mb.statix.constraints.CUser;
import mb.statix.constraints.Constraints;
import mb.statix.solver.IConstraint;

public class RuleUtilTest {

    private final static ILogger logger = LoggerUtils.logger(RuleUtilTest.class);

    public static void main(String[] args) {
        testUnorderedRules1();
        testUnorderedRules2();
        testInlineRules1();
        testInlineRules2();
        testInlineRules3();
        testInlineRules4();
        testOptimize();
    }


    private static void testUnorderedRules1() {
        final ITermVar v1 = B.newVar("", "p-1");
        final ITermVar v2 = B.newVar("", "p-2");
        final Pattern p1 = P.newVar(v1);
        final IConstraint body = Constraints.exists(Arrays.asList(v1), new CEqual(v1, v2));
        // @formatter:off
        final List<Rule> rules = Arrays.asList(
          Rule.of("c", Arrays.asList(P.newInt(1), P.newWld()), body)
        , Rule.of("c", Arrays.asList(p1, P.newAs(v2, P.newListTail(Arrays.asList(P.newWld()), P.newWld()))), body)
        , Rule.of("c", Arrays.asList(p1, P.newAs(v2, P.newInt(1))), body)
        , Rule.of("c", Arrays.asList(p1, P.newAs(v2, P.newWld())), body)
        );
        testUnorderedRules(rules);
    }

    private static void testUnorderedRules2() {
        final ITermVar v1 = B.newVar("", "p-1");
        final ITermVar v2 = B.newVar("", "p-2");
        final Pattern p1 = P.newVar(v1);
        final Pattern p2 = P.newVar(v2);
        final IConstraint body = new CTrue();
        // @formatter:off
        final List<Rule> rules = Arrays.asList(
          Rule.of("c", Arrays.asList(p1, P.newAs(v1, P.newInt(1))), body)
        , Rule.of("c", Arrays.asList(p1, p2), body)
        );
        // @formatter:on
        testUnorderedRules(rules);
    }

    private static void testUnorderedRules(List<Rule> rules) {
        logger.info("Ordered rules:");
        rules.forEach(r -> logger.info(" * {}", r));

        ImmutableCollection<Rule> newRules = RuleSet.of(rules).getAllOrderIndependentRules().values();
        logger.info("Unordered rules:");
        newRules.forEach(r -> logger.info(" * {}", r));
    }


    private static void testInlineRules1() {
        final Pattern p1 = P.newVar("p1");
        final Pattern p2 = P.newVar("p2");
        final ITermVar v1 = B.newVar("", "p1");
        final ITermVar v2 = B.newVar("", "p2");
        final Rule into = Rule.of("c", Arrays.asList(p1, P.newWld()),
                new CConj(new CTrue(), new CExists(Arrays.asList(v2), new CUser("c", Arrays.asList(v1, v2)))));
        final Rule rule = Rule.of("c", Arrays.asList(p1, p2), new CEqual(v1, v2));
        testInlineRules(rule, 0, into);
    }

    private static void testInlineRules2() {
        final Pattern p1 = P.newVar("p1");
        final Pattern p2 = P.newVar("p2");
        final ITermVar v1 = B.newVar("", "p1");
        final ITermVar v2 = B.newVar("", "p2");
        final Rule into = Rule.of("c", Arrays.asList(p1, P.newWld()),
                new CConj(new CTrue(), new CExists(Arrays.asList(v2), new CUser("c", Arrays.asList(B.newList(), v2)))));
        final Rule rule = Rule.of("c", Arrays.asList(P.newInt(42), p2), new CEqual(v1, v2));
        testInlineRules(rule, 0, into);
    }

    private static void testInlineRules3() {
        final Pattern p1 = P.newVar("p1");
        final Pattern p2 = P.newVar("p2");
        final ITermVar v1 = B.newVar("", "p1");
        final ITermVar v2 = B.newVar("", "p2");
        final Rule into = Rule.of("c", Arrays.asList(p1, P.newWld()),
                new CConj(new CTrue(), new CExists(Arrays.asList(v2), new CUser("c", Arrays.asList(v1, B.newList())))));
        final Rule rule = Rule.of("c", Arrays.asList(P.newInt(42), p2), new CEqual(v1, v2));
        testInlineRules(rule, 0, into);
    }

    private static void testInlineRules4() {
        final Pattern p1 = P.newVar("p1");
        final Pattern p2 = P.newVar("p2");
        final ITermVar v1 = B.newVar("", "p1");
        final ITermVar v2 = B.newVar("", "p2");
        final Rule into = Rule.of("c", Arrays.asList(p1, P.newWld()),
                new CConj(new CTrue(), new CExists(Arrays.asList(v2), new CUser("c", Arrays.asList(v1, B.newList())))));
        final Rule rule = Rule.of("c", Arrays.asList(P.newInt(42), p2), new CTrue());
        testInlineRules(rule, 0, into);
    }

    private static void testInlineRules(Rule rule, int i, Rule into) {
        logger.info("Inline");
        logger.info("* {}", rule);
        logger.info("into premise {} of", i);
        logger.info("* {}", into);
        final Optional<Rule> r = RuleUtil.inline(rule, i, into);
        if(r.isPresent()) {
            logger.info("gives");
            logger.info("* {}", r.get());
            final Rule rs = RuleUtil.simplify(r.get());
            logger.info("which simplifies to");
            logger.info("* {}", rs);
        } else {
            logger.info("failed");
        }
    }


    private static void testOptimize() {
        final ITermVar x = B.newVar("", "x");
        final ITermVar y = B.newVar("", "y");
        final ITermVar z = B.newVar("", "z");
        final ITermVar Ts = B.newVar("", "Ts");
        final ITermVar Us = B.newVar("", "Us");
        final IStringTerm A = B.newString("A");
        final ITermVar wld = B.newVar("", "_1");

        // @formatter:off
        final List<Rule> rules = Arrays.asList(
          Rule.of("", Arrays.asList(P.newVar(x)), new CEqual(x, A))
        , Rule.of("", Arrays.asList(P.newVar(x)), new CEqual(x, y))

        , Rule.of("", Arrays.asList(P.newVar(x)), new CExists(Arrays.asList(wld), new CEqual(x, B.newTuple(A, wld))))
        , Rule.of("", Arrays.asList(P.newVar(x)), new CExists(Arrays.asList(wld), new CEqual(x, B.newTuple(y, wld))))

        , Rule.of("", Arrays.asList(P.newAppl("Id", P.newVar(x))), new CEqual(x, A))
        , Rule.of("", Arrays.asList(P.newAppl("Id", P.newVar(x))), new CEqual(x, y))
        , Rule.of("", Arrays.asList(P.newAs(z, P.newAppl("Id", P.newVar(x)))), new CEqual(z, y))

        , Rule.of("", Arrays.asList(P.newVar(x)), new CEqual(x, B.newAppl("Id", A)))
        , Rule.of("", Arrays.asList(P.newVar(x)), new CEqual(x, B.newAppl("Id", y)))

        , Rule.of("", Arrays.asList(P.newVar(x)), new CEqual(y, B.newAppl("Id", A)))
        , Rule.of("", Arrays.asList(P.newVar(x)), new CEqual(y, B.newAppl("Id", x)))
        , Rule.of("", Arrays.asList(P.newVar(x)), new CExists(Arrays.asList(z), new CEqual(y, B.newAppl("Id", z))))

        , Rule.of("", Arrays.asList(P.newTuple(P.newVar(x), P.newVar(Ts))), new CConj(new CEqual(x, A), new CUser("p", Arrays.asList(Us, Ts))))
        , Rule.of("", Arrays.asList(P.newTuple(P.newVar(x), P.newVar(Ts))), new CConj(new CEqual(x, y), new CUser("p", Arrays.asList(Us, Ts))))

        , Rule.of("", Arrays.asList(P.newAs(z, P.newAppl("Id", P.newVar(x)))), new CEqual(z, B.newAppl("ID", y)))
        );
        // @formatter:on

        testOptimizeRules(rules);
    }

    private static void testOptimizeRules(List<Rule> rules) {
        for(Rule r : rules) {
            final Rule s = RuleUtil.optimizeRule(r);
            logger.info("Optimized {}", r);
            logger.info(" => {}", s.toString());
            if(!Set.Immutable.subtract(s.freeVars(), r.freeVars()).isEmpty()) {
                logger.error(" !! Introduced new free variables");
            }
        }
    }


}