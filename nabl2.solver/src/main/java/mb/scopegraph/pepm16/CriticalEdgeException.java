package mb.scopegraph.pepm16;

import java.util.List;

import com.google.common.collect.ImmutableList;

import mb.scopegraph.pepm16.esop15.CriticalEdge;

public class CriticalEdgeException extends Throwable {

    private static final long serialVersionUID = 1L;

    private final List<CriticalEdge> criticalEdges;

    public CriticalEdgeException(Iterable<CriticalEdge> criticalEdges) {
        super("incomplete", null, false, false);
        this.criticalEdges = ImmutableList.copyOf(criticalEdges);
        if(this.criticalEdges.isEmpty()) {
            throw new IllegalArgumentException("Critical edges cannot be empty.");
        }
    }

    public CriticalEdgeException(IScope scope, ILabel label) {
        this(ImmutableList.of(CriticalEdge.of(scope, label)));
    }

    public List<CriticalEdge> criticalEdges() {
        return criticalEdges;
    }

    public static CriticalEdgeException of(Iterable<CriticalEdgeException> exceptions) {
        ImmutableList.Builder<CriticalEdge> incompletes = ImmutableList.builder();
        exceptions.forEach(e -> incompletes.addAll(e.criticalEdges()));
        return new CriticalEdgeException(incompletes.build());
    }

    @Override public String getMessage() {
        final StringBuilder sb = new StringBuilder();
        sb.append("incomplete:");
        for(CriticalEdge ce : criticalEdges) {
            sb.append(" * ").append(ce);
        }
        return sb.toString();
    }

}