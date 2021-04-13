package mb.statix.solver.query;

import java.util.Optional;

import mb.nabl2.regexp.IRegExpMatcher;
import mb.nabl2.terms.ITerm;
import mb.scopegraph.oopsla20.reference.LabelWF;
import mb.scopegraph.oopsla20.reference.ResolutionException;

public class RegExpLabelWF implements LabelWF<ITerm> {

    private final IRegExpMatcher<ITerm> re;

    private RegExpLabelWF(IRegExpMatcher<ITerm> re) {
        this.re = re;
    }

    @Override public Optional<LabelWF<ITerm>> step(ITerm l) throws ResolutionException, InterruptedException {
        final IRegExpMatcher<ITerm> re = this.re.match(l);
        if(re.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(new RegExpLabelWF(re));
        }
    }

    @Override public boolean accepting() throws ResolutionException, InterruptedException {
        return re.isAccepting();
    }

    @Override public String toString() {
        return re.toString();
    }

    public static RegExpLabelWF of(IRegExpMatcher<ITerm> re) {
        return new RegExpLabelWF(re);
    }

}