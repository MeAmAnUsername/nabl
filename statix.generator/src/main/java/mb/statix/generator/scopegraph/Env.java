package mb.statix.generator.scopegraph;

import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableList;

import mb.scopegraph.oopsla20.path.IResolutionPath;

public class Env<S, L, D, X> {

    public final List<Match<S, L, D, X>> matches;
    public final List<Match<S, L, D, X>> rejects;

    private Env(ImmutableList<Match<S, L, D, X>> matches, ImmutableList<Match<S, L, D, X>> rejects) {
        this.matches = matches;
        this.rejects = rejects;
    }

    public boolean isEmpty() {
        return matches.isEmpty();
    }

    public boolean isNullable() {
        return matches.stream().allMatch(m -> m.condition.isPresent());
    }

    public static <S, L, D, X> Env<S, L, D, X> empty() {
        return new Env<>(ImmutableList.of(), ImmutableList.of());
    }

    public static <S, L, D, X> Env<S, L, D, X> match(IResolutionPath<S, L, D> path, Optional<X> x) {
        return new Env<>(ImmutableList.of(new Match<>(path, x)), ImmutableList.of());
    }

    public static <S, L, D, X> Builder<S, L, D, X> builder() {
        return new Builder<>();
    }

    public static class Builder<S, L, D, X> {

        private final ImmutableList.Builder<Match<S, L, D, X>> matches;
        private final ImmutableList.Builder<Match<S, L, D, X>> rejects;

        public Builder() {
            this.matches = ImmutableList.builder();
            this.rejects = ImmutableList.builder();
        }

        public void match(IResolutionPath<S, L, D> path, Optional<X> x) {
            match(new Match<>(path, x));
        }

        public void match(Match<S, L, D, X> match) {
            matches.add(match);
        }

        public void reject(Match<S, L, D, X> match) {
            if(!match.condition.isPresent()) {
                throw new IllegalArgumentException("Cannot reject unconditional match");
            }
            rejects.add(match);
        }

        public void match(Env<S, L, D, X> env) {
            matches.addAll(env.matches);
            rejects.addAll(env.rejects);
        }

        public void reject(Env<S, L, D, X> env) {
            if(env.matches.stream().anyMatch(m -> !m.condition.isPresent())) {
                throw new IllegalArgumentException("Cannot reject environment with unconditional matches");
            }
            rejects.addAll(env.matches);
            rejects.addAll(env.rejects);
        }

        public Env<S, L, D, X> build() {
            return new Env<>(matches.build(), rejects.build());
        }

    }

}