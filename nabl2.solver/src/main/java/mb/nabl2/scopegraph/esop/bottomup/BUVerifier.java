package mb.nabl2.scopegraph.esop.bottomup;

import java.util.Collection;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;
import org.metaborg.util.time.AggregateTimer;

import io.usethesource.capsule.Set;
import mb.nabl2.scopegraph.path.IResolutionPath;
import mb.nabl2.scopegraph.terms.Label;
import mb.nabl2.scopegraph.terms.Occurrence;
import mb.nabl2.scopegraph.terms.Scope;
import mb.nabl2.solver.ISolution;
import mb.nabl2.util.CapsuleUtil;

public class BUVerifier {

    private static ILogger logger = LoggerUtils.logger(BUVerifier.class);

    public static void verify(ISolution solution) {
        try {
            logger.info("resolving with bottom-up resolution");
            AggregateTimer timer = new AggregateTimer(false);
            final BUNameResolution<Scope, Label, Occurrence> nameResolution =
                    BUNameResolution.of(solution.scopeGraph(), solution.config().getResolutionParams());
            logger.info("verifying {} resolve entries", solution.nameResolutionCache().resolutionEntries().size());
            for(Entry<Occurrence, Collection<IResolutionPath<Scope, Label, Occurrence>>> entry : solution
                    .nameResolutionCache().resolutionEntries().entrySet()) {
                timer.start();
                final Collection<IResolutionPath<Scope, Label, Occurrence>> result =
                        nameResolution.resolve(entry.getKey());
                timer.stop();
                verifyEquals("resolve", entry.getValue(), result);
            }
            logger.info("verifying {} visible entries", solution.nameResolutionCache().visibilityEntries().size());
            for(Entry<Scope, Collection<Occurrence>> entry : solution.nameResolutionCache().visibilityEntries()
                    .entrySet()) {
                timer.start();
                final Collection<Occurrence> result = nameResolution.visible(entry.getKey());
                timer.stop();
                verifyEquals("visible", entry.getValue(), result);
            }
            logger.info("verifying {} reachable entries", solution.nameResolutionCache().reachabilityEntries().size());
            for(Entry<Scope, Collection<Occurrence>> entry : solution.nameResolutionCache().reachabilityEntries()
                    .entrySet()) {
                timer.start();
                final Collection<Occurrence> result = nameResolution.reachable(entry.getKey());
                timer.stop();
                verifyEquals("reachable", entry.getValue(), result);
            }
            logger.info("bottom-up resolution took {} s",
                    (double) timer.total() / (double) TimeUnit.NANOSECONDS.convert(1l, TimeUnit.SECONDS));
        } catch(Exception e) {
            logger.error("bottom-up resolution failed", e);
        }
    }

    private static <E> void verifyEquals(String tag, Collection<E> expected, Collection<E> actual) {
        final Set.Immutable<E> expectedSet = CapsuleUtil.toSet(expected);
        final Set.Immutable<E> actualSet = CapsuleUtil.toSet(actual);
//        logger.info("[{}] matched {}", tag, Set.Immutable.intersect(expectedSet, actualSet).size());
        final Set.Immutable<E> missing = Set.Immutable.subtract(expectedSet, actualSet);
        if(!missing.isEmpty()) {
            logger.error("[{}] missing {}", tag, missing);
        }
        final Set.Immutable<E> extra = Set.Immutable.subtract(actualSet, expectedSet);
        if(!extra.isEmpty()) {
            logger.error("[{}] extra {}", tag, extra);
        }
    }

}