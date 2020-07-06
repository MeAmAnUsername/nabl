package mb.nabl2.scopegraph.terms.path;

import javax.annotation.Nullable;

import org.immutables.serial.Serial;
import org.immutables.value.Value;

import io.usethesource.capsule.Set;
import mb.nabl2.scopegraph.ILabel;
import mb.nabl2.scopegraph.IOccurrence;
import mb.nabl2.scopegraph.IScope;
import mb.nabl2.scopegraph.path.IResolutionPath;
import mb.nabl2.scopegraph.path.IScopePath;
import mb.nabl2.util.collections.ConsList;

@Value.Immutable
@Serial.Version(value = 42L)
abstract class AResolutionPath<S extends IScope, L extends ILabel, O extends IOccurrence>
        implements IResolutionPath<S, L, O> {

    @Value.Parameter @Override public abstract O getReference();

    @Value.Parameter @Override public abstract IScopePath<S, L, O> getPath();

    @Value.Parameter @Override public abstract O getDeclaration();

    @Value.Check public @Nullable AResolutionPath<S, L, O> check() {
        if(!IOccurrence.match(getReference(), getDeclaration())) {
            return null;
        }
        if(getPath().getImports().contains(getReference())) {
            return null;
        }
        return this;
    }

    @Value.Lazy @Override public Set.Immutable<O> getImports() {
        return getPath().getImports();
    }

    @Value.Lazy @Override public Set.Immutable<S> getScopes() {
        return getPath().getScopes();
    }

    @Value.Lazy @Override public ConsList<L> getLabels() {
        return getPath().getLabels();
    }

    @Override public Iterable<IResolutionPath<S, L, O>> getImportPaths() {
        return getPath().getImportPaths();
    }

    @Value.Lazy @Override public abstract int hashCode();

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getReference());
        sb.append(Paths.PATH_SEPERATOR);
        sb.append("R");
        sb.append(Paths.PATH_SEPERATOR);
        sb.append(getPath());
        sb.append(Paths.PATH_SEPERATOR);
        sb.append("D");
        sb.append(Paths.PATH_SEPERATOR);
        sb.append(getDeclaration());
        return sb.toString();
    }

}