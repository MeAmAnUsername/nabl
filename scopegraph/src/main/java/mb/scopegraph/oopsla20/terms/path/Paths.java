package mb.scopegraph.oopsla20.terms.path;

import java.util.Optional;

import mb.scopegraph.oopsla20.path.IResolutionPath;
import mb.scopegraph.oopsla20.path.IScopePath;
import mb.scopegraph.oopsla20.path.IStep;

public final class Paths {

    public static final String PATH_SEPARATOR = " ";

    public static <S, L> IStep<S, L> edge(S source, L label, S target) {
        return Step.of(source, label, target);
    }

    public static <S, L> IScopePath<S, L> empty(S scope) {
        return EmptyScopePath.of(scope);
    }

    public static <S, L, D> IResolutionPath<S, L, D> resolve(IScopePath<S, L> path, D datum) {
        return ResolutionPath.of(path, datum);
    }

    public static <S, L> Optional<IScopePath<S, L>> append(IScopePath<S, L> left, IScopePath<S, L> right) {
        return Optional.ofNullable(ComposedScopePath.of(left, right));
    }

    public static <S, L, D> Optional<IResolutionPath<S, L, D>> append(IScopePath<S, L> left,
            IResolutionPath<S, L, D> right) {
        return Optional.ofNullable(ComposedScopePath.of(left, right.getPath()))
                .map(p -> ResolutionPath.of(p, right.getDatum()));
    }

}