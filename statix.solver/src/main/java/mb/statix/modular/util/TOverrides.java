package mb.statix.modular.util;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;

import mb.statix.modular.solver.concurrent.locking.DummyReadWriteLock;
import mb.statix.util.collection.MapMultimap;

public class TOverrides {
    /** Redirect STX_solve_constraint to MSTX_solve_constraint. */
    public static boolean MODULES_OVERRIDE = false;
    /** If the log level should be overridden to the value below. */
    public static boolean OVERRIDE_LOGLEVEL = true;
    /** The log level to use, has no effect if OVERRIDE_LOGLEVEL is false. */
    public static String LOGLEVEL = "none"; //"debug" "none" "info"
    /** If concurrency should be used. Uses the number of threads below. */
    public static boolean CONCURRENT = false;
    /** The number of threads for concurrency. Has no effect if CONCURRENT is false. */
    public static int THREADS = 4;
    
    /** If a scope graph should be generated after a solve_constraint call. */
    public static boolean OUTPUT_SCOPE_GRAPH_SINGLE = true;
    /** If a scope graph should be generated after a solve_multi_file call. */
    public static boolean OUTPUT_SCOPE_GRAPH_MULTI = true;
    
    /** If a diff should be generated after an incremental analysis. */
    public static boolean OUTPUT_DIFF = false;
    
    /** If split modules should be used. */
    public static boolean SPLIT_MODULES = false;
    
    /** If enabled, cross module unification is made possible. */
    public static boolean CROSS_MODULE_UNIFICATION = false;
    
    /** If enabled (default), solving of a module stops immediately on failure. */
    public static boolean STOP_IMMEDIATELY_ON_FAILURE = false;
    
    /** Maximum number of phases that we will attempt before analysis is cut off. */
    public static int MAX_PHASES = 50;
    
    /**
     * The value of this integer determines what locking approach scope graphs use.
     * <p>
     * 0 -> all modules use reentrant read write locks<br>
     * 1 -> all modules use synchronized<br>
     * 2 -> all modules use synchronized, except for the <b>first</b> level (root)<br>
     * 3 -> all modules use synchronized, except for the first <b>two</b> levels<br>
     * 4 -> all modules use synchronized, except for the first <b>three</b> levels<br>
     * 5 -> ...
     */
    public static volatile int SYNC_SCOPEGRAPHS = 0;
    
    public static String print() {
        return "Concurrent=" + (CONCURRENT ? THREADS : "false") +
                ", Loglevel=" + (OVERRIDE_LOGLEVEL ? LOGLEVEL : "not overridden") +
                ", UseObserverMechanismSelf=" + TOptimizations.USE_OBSERVER_MECHANISM_FOR_SELF +
                ", DependencyObservers=[" + TSettings.getDependencyObservers().stream().map(o -> o.getClass().getSimpleName()).collect(Collectors.joining(", ")) + "]";
    }
    
    /**
     * If concurrency is enabled, this method returns a {@link ConcurrentHashMap}. Otherwise, this
     * method returns a normal {@link HashMap}.
     * 
     * @return
     *      either a concurrent or non concurrent hash map
     */
    public static <K, V> Map<K, V> hashMap() {
        return CONCURRENT ? new ConcurrentHashMap<>() : new HashMap<>();
    }
    
    /**
     * If concurrency is enabled, this method returns a {@link ConcurrentHashMap}. Otherwise, this
     * method returns a normal {@link HashMap}.
     * 
     * All the mappings in the given map are added to the returned map.
     * 
     * @param map
     *      the map
     * 
     * @return
     *      either a concurrent or non concurrent hash map
     */
    public static <K, V> Map<K, V> hashMap(Map<K, V> map) {
        return CONCURRENT ? new ConcurrentHashMap<>(map) : new HashMap<>(map);
    }
    
    /**
     * If concurrency is enabled, this method returns a {@link MapMultimap} with a
     * {@link ConcurrentHashMap} as backing map and synchronized multimaps for the backing
     * multimaps. Otherwise, this method returns a normal {@link MapMultimap} (backed by a
     * {@link HashMap} and a normal hash keys, hash set values multimap.
     * 
     * @return
     *      either a concurrent or non concurrent version of the multimap
     */
    public static <K1, K2, V> MapMultimap<K1, K2, V> mapSetMultimap() {
        return CONCURRENT
                ? MapMultimap.concurrent(x -> Multimaps.synchronizedMultimap(MultimapBuilder.hashKeys().hashSetValues().build()))
                : new MapMultimap<>();
    }
    
    public static <K1, K2, V> MapMultimap<K1, K2, V> mapListMultimap() {
        return CONCURRENT
                ? MapMultimap.concurrent(x -> Multimaps.synchronizedMultimap(MultimapBuilder.hashKeys().arrayListValues().build()))
                : new MapMultimap<>();
    }
    
    /**
     * If concurrency is enabled, this method returns a concurrent hash set. Otherwise, this
     * method returns a normal {@link HashSet}.
     * 
     * @return
     *      either a concurrent or non concurrent set
     */
    public static <E> Set<E> set() {
        return CONCURRENT ? ConcurrentHashMap.newKeySet() : new HashSet<>();
    }
    
    /**
     * If concurrency is enabled, this method returns a concurrent hash set. Otherwise, this
     * method returns a normal {@link HashSet}.
     * 
     * The given elements are added to the set.
     * 
     * @param elements
     *      the elements
     * 
     * @return
     *      either a concurrent or non concurrent set
     */
    public static <E> Set<E> set(Collection<E> elements) {
        if (CONCURRENT) {
            Set<E> set = ConcurrentHashMap.newKeySet();
            set.addAll(elements);
            return set;
        }
        
        return new HashSet<>(elements);
    }
    
    /**
     * Wraps the given set into a synchronized set if concurrency is enabled.
     * 
     * @param base
     *      the set to wrap
     * 
     * @return
     *      the set itself, or the wrapped set
     */
    public static <E> Set<E> synchronizedSet(Set<E> base) {
        return CONCURRENT ? Collections.synchronizedSet(base) : base;
    }
    
    /**
     * If concurrency is enabled, this method returns a {@link ReentrantReadWriteLock}. Otherwise,
     * this method returns a dummy lock.
     * 
     * @return
     *      either a read write lock or a dummy lock
     */
    public static ReadWriteLock readWriteLock() {
        return CONCURRENT ? new ReentrantReadWriteLock() : DummyReadWriteLock.of();
    }
}
