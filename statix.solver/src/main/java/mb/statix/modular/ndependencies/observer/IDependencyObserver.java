package mb.statix.modular.ndependencies.observer;

import java.util.Collection;

import mb.statix.modular.dependencies.Dependency;
import mb.statix.modular.solver.Context;

public interface IDependencyObserver {
    /**
     * Called whenever a dependency is added.
     * 
     * @param dependency
     *      the dependency
     */
    public void onDependencyAdded(Dependency dependency);
    
    /**
     * Removes the given dependencies.
     * 
     * @param dependencies
     *      the dependencies to remove
     */
    public void removeDependencies(Collection<Dependency> dependencies);
    
    /**
     * Resets the dependencies of the given module.
     * 
     * @param module
     *      the module to reset
     */
    public default void resetDependencies(String module) {
        removeDependencies(Context.context().getDependencies(module).getDependencies().values());
    }
}
