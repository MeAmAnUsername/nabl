package mb.nabl2.util.collections;

import java.io.Serializable;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import io.usethesource.capsule.Map;
import mb.nabl2.util.CapsuleUtil;

public abstract class MultiSet<E> implements Iterable<E> {

    protected abstract Map<E, Integer> elements();

    public boolean isEmpty() {
        return elements().isEmpty();
    }

    public int size() {
        return elements().values().stream().mapToInt(i -> i).sum();
    }

    public int count(E e) {
        return elements().getOrDefault(e, 0);
    }

    public boolean contains(E e) {
        return elements().containsKey(e);
    }

    public Set<Entry<E, Integer>> entrySet() {
        return elements().entrySet();
    }

    public java.util.Set<E> elementSet() {
        return elements().keySet();
    }

    @Override public Iterator<E> iterator() {
        return new MultiSetIterator();
    }

    public Map.Immutable<E, Integer> toMap() {
        return CapsuleUtil.toMap(elements());
    }

    public static class Immutable<E> extends MultiSet<E> implements Serializable {

        private static final long serialVersionUID = 1L;

        private final Map.Immutable<E, Integer> elements;

        private Immutable(Map.Immutable<E, Integer> elements) {
            this.elements = elements;
        }

        @Override protected Map<E, Integer> elements() {
            return elements;
        }

        public Immutable<E> set(E e, int n) {
            if(n < 0) {
                throw new IllegalArgumentException("count must be positive");
            }
            if(n > 0) {
                return new MultiSet.Immutable<>(elements.__put(e, n));
            } else {
                return new MultiSet.Immutable<>(elements.__remove(e));
            }
        }

        public Immutable<E> add(E e) {
            return add(e, 1);
        }

        public Immutable<E> add(E e, int n) {
            if(n < 0) {
                throw new IllegalArgumentException("count must be positive");
            }
            final int c = elements.getOrDefault(e, 0) + n;
            if(c > 0) {
                return new MultiSet.Immutable<>(elements.__put(e, c));
            } else {
                return new MultiSet.Immutable<>(elements.__remove(e));
            }
        }

        public Immutable<E> addAll(Iterable<E> es) {
            Immutable<E> result = this;
            for(E e : es) {
                result = result.add(e);
            }
            return result;
        }

        public Immutable<E> remove(E e) {
            return remove(e, 1);
        }

        public Immutable<E> remove(E e, int n) {
            if(n < 0) {
                throw new IllegalArgumentException("count must be positive");
            }
            final int c = Math.max(0, elements.getOrDefault(e, 0) - n);
            if(c > 0) {
                return new MultiSet.Immutable<>(elements.__put(e, c));
            } else {
                return new MultiSet.Immutable<>(elements.__remove(e));
            }

        }

        public Immutable<E> removeAll(E e) {
            return new MultiSet.Immutable<>(elements.__remove(e));
        }

        public MultiSet.Transient<E> melt() {
            return new MultiSet.Transient<>(elements.asTransient());
        }

        public static <E> MultiSet.Immutable<E> of() {
            return new MultiSet.Immutable<>(Map.Immutable.of());
        }

    }

    public static class Transient<E> extends MultiSet<E> {

        private Map.Transient<E, Integer> elements;

        private Transient(Map.Transient<E, Integer> elements) {
            this.elements = elements;
        }

        @Override protected Map<E, Integer> elements() {
            return elements;
        }

        /**
         * Set an element to n.
         * 
         * @param e
         *            Element to be set
         * @param n
         *            New count
         * @return Old count for the element
         */
        public int set(E e, int n) {
            if(n < 0) {
                throw new IllegalArgumentException("count must be positive");
            }
            final Integer c;
            if(n > 0) {
                c = elements.__put(e, n);
            } else {
                c = elements.__remove(e);
            }
            return c != null ? c : 0;
        }

        /**
         * Add an element once.
         * 
         * @param e
         *            Element to be added
         * @return New count for the element.
         */
        public int add(E e) {
            return add(e, 1);
        }

        /**
         * Add an element n times.
         * 
         * @param e
         *            Element to be added
         * @param n
         *            Additions, greater or equal to zero
         * @return New count for the element
         */
        public int add(E e, int n) {
            if(n < 0) {
                throw new IllegalArgumentException("count must be positive");
            }
            final int c = elements.getOrDefault(e, 0) + n;
            if(c > 0) {
                elements.__put(e, c);
            } else {
                elements.__remove(e);
            }
            return c;
        }

        public void addAll(Iterable<E> es) {
            for(E e : es) {
                this.add(e);
            }
        }

        /**
         * Remove an element once.
         * 
         * @param e
         *            Element to be removed
         * @return New count for the element
         */
        public int remove(E e) {
            return remove(e, 1);
        }

        public void removeAll(Iterable<E> es) {
            for(E e : es) {
                this.remove(e);
            }
        }

        /**
         * Remove an element up to n times.
         * 
         * @param e
         *            Element to be removed
         * @param n
         *            Removals, greater or equal to zero
         * @return New count for the element
         */
        public int remove(E e, int n) {
            if(n < 0) {
                throw new IllegalArgumentException("count must be positive");
            }
            final int c = Math.max(0, elements.getOrDefault(e, 0) - n);
            if(c > 0) {
                elements.__put(e, c);
            } else {
                elements.__remove(e);
            }
            return c;
        }

        /**
         * Remove an element completely.
         * 
         * @return Old count for the element
         */
        public int removeAll(E e) {
            final int c = elements.getOrDefault(e, 0);
            elements.__remove(e);
            return c;
        }

        public MultiSet.Immutable<E> freeze() {
            return new MultiSet.Immutable<>(elements.freeze());
        }

        public static <E> MultiSet.Transient<E> of() {
            return new MultiSet.Transient<>(Map.Transient.of());
        }


    }

    @Override public String toString() {
        return elements().entrySet().stream().map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private class MultiSetIterator implements Iterator<E> {

        private Iterator<Map.Entry<E, Integer>> it = elements().entryIterator();
        private E next;
        private int count;

        @Override public boolean hasNext() {
            return (next != null && count > 0) || it.hasNext();
        }

        @Override public E next() {
            if(next == null || count <= 0) {
                final Map.Entry<E, Integer> entry = it.next();
                next = entry.getKey();
                count = entry.getValue();
            }
            count -= 1;
            return next;
        }

    }

}