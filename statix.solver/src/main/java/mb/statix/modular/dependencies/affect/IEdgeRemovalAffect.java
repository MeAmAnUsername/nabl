package mb.statix.modular.dependencies.affect;

import java.util.Comparator;

import mb.nabl2.terms.ITerm;
import mb.statix.modular.dependencies.Dependency;
import mb.statix.scopegraph.terms.Scope;

public interface IEdgeRemovalAffect {
    public static final Comparator<IEdgeRemovalAffect> COMPARATOR = (a, b) -> -Integer.compare(a.edgeRemovalAffectScore(), b.edgeRemovalAffectScore());
    /**
     * @param scope
     *      the (source) scope of the edge
     * @param label
     *      the label of the edge
     * 
     * @return
     *      the dependencies that can be affected by the removal of the given edge
     */
    public Iterable<Dependency> affectedByEdgeRemoval(Scope scope, ITerm label);
    
    /**
     * @return
     *      the score (lower is better) for how well this predicts the impact of edge removal
     */
    public int edgeRemovalAffectScore();
}
