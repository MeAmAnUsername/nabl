package mb.nabl2.regexp;

import java.io.Serializable;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;

import mb.nabl2.regexp.impl.RegExpNormalizingBuilder;
import mb.nabl2.regexp.impl.RegExps;

public class RegExpMatcher<S> implements IRegExpMatcher<S>, Serializable {

    private static final long serialVersionUID = 42L;

    private final State<S> state;

    private RegExpMatcher(State<S> state) {
        this.state = state;
    }

    @Override public IRegExp<S> regexp() {
        return state.regexp;
    }

    @Override public RegExpMatcher<S> match(S symbol) {
        return new RegExpMatcher<>(state.symbolTransitions.getOrDefault(symbol, state.defaultTransition));
    }

    @Override public IRegExpMatcher<S> match(Iterable<S> symbols) {
        RegExpMatcher<S> matcher = this;
        for(S symbol : symbols) {
            matcher = matcher.match(symbol);
        }
        return matcher;
    }

    @Override public boolean isAccepting() {
        return state.isNullable;
    }

    @Override public boolean isFinal() {
        return !state.nonFinal;
    }

    @Override public String toString() {
        return state.regexp.toString();
    }

    public static <S> IRegExpMatcher<S> create(final IRegExp<S> initial) {
        final IAlphabet<S> alphabet = RegExps.alphabet(initial);
        final RegExpNormalizingBuilder<S> builder = new RegExpNormalizingBuilder<>(alphabet);
        final IRegExp<S> empty = builder.emptySet();

        final List<Deriver<S>> derivers = Lists.newArrayList();
        for(S symbol : alphabet) {
            derivers.add(new Deriver<>(symbol, builder));
        }
        final Deriver<S> defaultDeriver = new Deriver<>(null, builder);

        final Map<IRegExp<S>, Map<S, IRegExp<S>>> stateTransitions = Maps.newHashMap();
        final Map<IRegExp<S>, IRegExp<S>> defaultTransitions = Maps.newHashMap();
        final Map<IRegExp<S>, Set<IRegExp<S>>> reverseTransitions = Maps.newHashMap();
        final Deque<IRegExp<S>> worklist = Queues.newArrayDeque();
        worklist.push(initial);
        worklist.push(empty);
        while(!worklist.isEmpty()) {
            final IRegExp<S> state = worklist.pop();
            final Map<S, IRegExp<S>> transitions = Maps.newHashMapWithExpectedSize(derivers.size());
            if(!stateTransitions.containsKey(state)) {
                for(Deriver<S> deriver : derivers) {
                    final IRegExp<S> nextState = builder.apply(deriver.apply(state));
                    Set<IRegExp<S>> reverseStates;
                    if((reverseStates = reverseTransitions.get(nextState)) == null) {
                        reverseTransitions.put(nextState, (reverseStates = Sets.newHashSet()));
                    }
                    reverseStates.add(state);
                    transitions.put(deriver.getSymbol(), nextState);
                    worklist.push(nextState);
                }
                {
                    final IRegExp<S> defaultState = builder.apply(defaultDeriver.apply(state));
                    Set<IRegExp<S>> reverseStates;
                    if((reverseStates = reverseTransitions.get(defaultState)) == null) {
                        reverseTransitions.put(defaultState, (reverseStates = Sets.newHashSet()));
                    }
                    reverseStates.add(state);
                    defaultTransitions.put(state, defaultState);
                    worklist.push(defaultState);
                }

                stateTransitions.put(state, transitions);
            }
        }

        for(IRegExp<S> state : stateTransitions.keySet()) {
            if(RegExps.isNullable(state)) {
                worklist.push(state);
            }
        }
        final Set<IRegExp<S>> visited = Sets.newHashSet();
        final Set<IRegExp<S>> nonFinal = Sets.newHashSet();
        while(!worklist.isEmpty()) {
            final IRegExp<S> state = worklist.pop();
            if(!visited.contains(state)) {
                visited.add(state);
                if(reverseTransitions.containsKey(state)) {
                    for(IRegExp<S> nextState : reverseTransitions.get(state)) {
                        nonFinal.add(nextState);
                        worklist.push(nextState);
                    }
                }
            }
        }
        final Set<IRegExp<S>> isNullable =
                stateTransitions.keySet().stream().filter(RegExps::isNullable).collect(ImmutableSet.toImmutableSet());

        // convert maps to object graph
        Map<IRegExp<S>, State<S>> states = Maps.newHashMapWithExpectedSize(stateTransitions.size());
        for(IRegExp<S> state : stateTransitions.keySet()) {
            final State<S> _state;
            states.put(state, _state = new State<>(state));
            _state.isNullable = isNullable.contains(state);
            _state.nonFinal = nonFinal.contains(state);
        }
        for(IRegExp<S> state : stateTransitions.keySet()) {
            final State<S> _state = states.get(state);
            _state.defaultTransition = states.get(defaultTransitions.get(state));
            _state.symbolTransitions = stateTransitions.get(state).entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey(), e -> states.get(e.getValue())));
        }

        return new RegExpMatcher<>(states.get(initial));
    }

    private static class State<S> implements Serializable {

        private static final long serialVersionUID = 1L;

        private final IRegExp<S> regexp;

        private Map<S, State<S>> symbolTransitions;
        private State<S> defaultTransition;
        private boolean nonFinal;
        private boolean isNullable;

        private State(IRegExp<S> regexp) {
            this.regexp = regexp;
        }

        @Override public String toString() {
            return regexp.toString();
        }

    }

    @Override public boolean equals(Object o) {
        if(this == o)
            return true;
        if(o == null || getClass() != o.getClass())
            return false;
        RegExpMatcher<?> that = (RegExpMatcher<?>) o;
        return Objects.equals(state, that.state);
    }

    @Override public int hashCode() {
        return Objects.hash(state);
    }

}