package mb.nabl2.scopegraph.esop.bottomup;

import java.util.Iterator;

import mb.nabl2.relations.IRelation;
import mb.nabl2.scopegraph.ILabel;
import mb.nabl2.scopegraph.IOccurrence;
import mb.nabl2.scopegraph.IScope;
import mb.nabl2.scopegraph.path.IDeclPath;
import mb.nabl2.scopegraph.path.IResolutionPath;
import mb.nabl2.scopegraph.path.IScopePath;
import mb.nabl2.scopegraph.path.IStep;

public class BUComparator<S extends IScope, L extends ILabel, O extends IOccurrence> {

    private final L labelD;
    private final IRelation<L> topOrder;
    private final IRelation<L> importOrder;

    public BUComparator(L labelD, IRelation<L> topOrder, IRelation<L> importOrder) {
        this.labelD = labelD;
        this.topOrder = topOrder;
        this.importOrder = importOrder;
    }

    public Integer compare(IResolutionPath<S, L, O> path1, IResolutionPath<S, L, O> path2) {
        return compare(path1, path2, topOrder);
    }

    private Integer compare(IResolutionPath<S, L, O> path1, IResolutionPath<S, L, O> path2, IRelation<L> order) {
        if(!path1.getReference().equals(path2.getReference())) {
            return null;
        }
        return compare(path1.getPath(), path2.getPath(), order);
    }

    public Integer compare(IDeclPath<S, L, O> path1, IDeclPath<S, L, O> path2) {
        return compare(path1, path2, topOrder);
    }

    private Integer compare(IDeclPath<S, L, O> path1, IDeclPath<S, L, O> path2, IRelation<L> order) {
        if(!path1.getDeclaration().getSpacedName().equals(path2.getDeclaration().getSpacedName())) {
            return null;
        }
        return compare(path1.getPath(), path2.getPath(), order);
    }

    private Integer compare(IScopePath<S, L, O> path1, IScopePath<S, L, O> path2, IRelation<L> order) {
        if(!path1.getSource().equals(path2.getSource())) {
            // paths with different sources are unordered
            return null;
        }
        final Iterator<IStep<S, L, O>> it1 = path1.iterator();
        final Iterator<IStep<S, L, O>> it2 = path2.iterator();
        while(it1.hasNext() && it2.hasNext()) {
            final Integer result = compare(it1.next(), it2.next(), order);
            if(result == null || result != 0) {
                return result;
            }
        }
        final L l1 = it1.hasNext() ? it1.next().getLabel() : labelD;
        final L l2 = it2.hasNext() ? it2.next().getLabel() : labelD;
        return compare(l1, l2, order);
    }

    private Integer compare(IStep<S, L, O> step1, IStep<S, L, O> step2, IRelation<L> order) {
        if(!step1.getSource().equals(step2.getSource())) {
            // steps with different sources are unordered
            return null;
        }
        final Integer lc = compare(step1.getLabel(), step2.getLabel(), order);
        if(lc == null || lc != 0) {
            return lc;
        }
        // at this point, source and label are equal
        if(!step1.getTarget().equals(step2.getTarget())) {
            // steps with same labels but different targets are unordered
            return null;
        }
        // at this point, the two steps have the same source, label, and target
        final IResolutionPath<S, L, O> path1 = importPath(step1);
        final IResolutionPath<S, L, O> path2 = importPath(step2);
        if(path1 == null && path2 == null) {
            // direct steps, the steps are equal
            return 0;
        } else if(path1 != null && path2 != null) {
            // both steps are imports, compare import paths
            return compare(path1, path2, importOrder);
        } else {
            // steps are unordered
            return null;
        }
    }

    private Integer compare(L l1, L l2, IRelation<L> order) {
        if(l1.equals(l2)) {
            return 0;
        } else if(order.contains(l1, l2)) {
            return -1;
        } else if(order.contains(l2, l1)) {
            return 1;
        } else {
            return null;
        }
    }

    private IResolutionPath<S, L, O> importPath(IStep<S, L, O> step) {
        return step.match(new IStep.ICases<S, L, O, IResolutionPath<S, L, O>>() {
            @Override public IResolutionPath<S, L, O> caseE(S source, L label, S target) {
                return null;
            }

            @Override public IResolutionPath<S, L, O> caseN(S source, L label, IResolutionPath<S, L, O> importPath,
                    S target) {
                return importPath;
            }
        });
    }


}