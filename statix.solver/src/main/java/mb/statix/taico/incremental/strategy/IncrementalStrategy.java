package mb.statix.taico.incremental.strategy;

import static mb.statix.taico.module.ModuleCleanliness.CLEAN;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.metaborg.util.functions.Function1;

import mb.nabl2.terms.ITerm;
import mb.nabl2.terms.Terms;
import mb.nabl2.terms.matching.TermMatch.IMatcher;
import mb.statix.constraints.CUser;
import mb.statix.scopegraph.terms.Scope;
import mb.statix.solver.IConstraint;
import mb.statix.taico.dependencies.DependencyManager;
import mb.statix.taico.dependencies.NameDependencies;
import mb.statix.taico.incremental.changeset.IChangeSet;
import mb.statix.taico.incremental.manager.IncrementalManager;
import mb.statix.taico.module.IModule;
import mb.statix.taico.module.ModuleCleanliness;
import mb.statix.taico.module.ModulePaths;
import mb.statix.taico.scopegraph.reference.ModuleDelayException;
import mb.statix.taico.solver.SolverContext;
import mb.statix.taico.solver.state.IMState;

/**
 * The incremental strategy determines how the incremental solving proceeds.
 * 
 * NOTE: The strategy does not have state itself.
 */
public abstract class IncrementalStrategy {
    
    //---------------------------------------------------------------------------------------------
    // Change Sets
    //---------------------------------------------------------------------------------------------
    
    /**
     * Creates a new changeset for this strategy.
     * 
     * @param oldContext
     *      the previous context
     * @param added
     *      the names (first level)of the modules that were added
     * @param removed
     *      the names (first level) or ids of the modules that were removed
     * @param changed
     *      the names (first level) or ids of the modules that were changed
     * 
     * @return
     *      the change set
     */
    public abstract IChangeSet createChangeSet(SolverContext oldContext,
            Collection<String> added, Collection<String> changed, Collection<String> removed);
    
    //---------------------------------------------------------------------------------------------
    // Module access / delay
    //---------------------------------------------------------------------------------------------
    
    //TODO Move this method to the incremental manager
    /**
     * Gets a child module of the given requester. The strategy is free to
     * delay the request or to answer the request with an old version of the module.
     * 
     * @param context
     *      the solver context
     * @param oldContext
     *      the previous context (can be null)
     * @param requester
     *      the module requesting the access
     * @param childId
     *      the id of the child
     * 
     * @return
     *      the child module, or null if no child module exists.
     * 
     * @throws ModuleDelayException
     *      If the child access needs to be delayed.
     */
    public abstract IModule getChildModule(SolverContext context, SolverContext oldContext,
            IModule requester, String childId) throws ModuleDelayException;
    
    //TODO Move this method to the incremental manager
    /**
     * Method for handling the request to get a module from the context. The strategy is free to
     * delay the request or to answer the request with an old version of the module.
     * 
     * @param context
     *      the context on which the module is requested
     * @param oldContext
     *      the previous context (can be null)
     * @param requesterId
     *      the id of the requester of the module
     * @param id
     *      the id of the module being requested
     * 
     * @return
     *      the module that was requested, or null if such a module does not exist
     * 
     * @throws ModuleDelayException
     *      If the access is not allowed (yet) in the current context phase.
     */
    public abstract IModule getModule(SolverContext context, SolverContext oldContext,
            String requesterId, String id) throws ModuleDelayException;
    
    //---------------------------------------------------------------------------------------------
    // Phasing
    //---------------------------------------------------------------------------------------------

    /**
     * Creates file level modules from the given map of module names to constraints. This method
     * can reuse existing modules where convenient.
     * <p>
     * All modules in the returned map will have a solver created for them, all modules that are
     * not in the map will be available, but won't be actively solving themselves.
     * <p>
     * Implementors should use {@link #createFileModule(SolverContext, String, Set)} and
     * {@link #reuseOldModule(SolverContext, IModule)} to create or reuse modules.
     * 
     * @param context
     *      the context
     * @param changeSet
     *      the change set
     * @param moduleConstraints
     *      the map from module name to the initialization constraints
     * 
     * @return
     *      a map from module to initialization constraints
     * 
     * @see SolverContext#getPhase()
     */
    public Map<IModule, IConstraint> createInitialModules(SolverContext context,
            IChangeSet changeSet, Map<String, IConstraint> moduleConstraints) {
        
        Map<IModule, IConstraint> newModules = new HashMap<>();
        moduleConstraints.entrySet().stream()
        .sorted((a, b) -> ModulePaths.INCREASING_PATH_LENGTH.compare(a.getKey(), b.getKey()))
        .forEachOrdered(entry -> {
            System.err.println("[IS] Encountered entry for " + entry.getKey());
            IModule oldModule = context.getOldContext().map(c -> c.getModuleByNameOrId(entry.getKey())).orElse(null);
            
            if (oldModule == null || oldModule.getTopCleanliness() != CLEAN) {
                IModule module = createModule(context, changeSet, entry.getKey(), entry.getValue(), oldModule);
                if (module != null) newModules.put(module, entry.getValue());
            } else {
                //Old module is clean, we can reuse it
                reuseOldModule(context, changeSet, oldModule);
            }
        });
        
        return newModules;
    }
    
    /**
     * Creates a new module (child or file) with the given init constraint.
     * 
     * @param context
     *      the context 
     * @param childNameOrId
     *      the name of the child (file level) or the id of the child
     * @param initConstraint
     *      the init constraint of the child
     * @param oldModule
     *      the old module, or null if not available
     * 
     * @return
     *      the module
     */
    protected IModule createModule(SolverContext context, IChangeSet changeSet, String childNameOrId, IConstraint initConstraint, @Nullable IModule oldModule) {
        int len = ModulePaths.pathLength(childNameOrId);
        if (len == 1) {
            //This is not a path, but just a file name
            return createFileModule(context, childNameOrId, initConstraint, oldModule);
        } else if (len == 2) {
            //This is a path, but it is still about a top level module
            return createFileModule(context, ModulePaths.getName(childNameOrId), initConstraint, oldModule);
        } else {
            //This is a path of a module that is not top level.
            return createChildModule(context, changeSet, childNameOrId, initConstraint);
        }
    }
    
    /**
     * Creates a new file module from the given module and initconstraints.
     * Strategies can override this method to change the behavior.
     * 
     * @param context
     *      the context
     * @param childName
     *      the name of the child
     * @param initConstraint
     *      the initialization constraint
     * @param oldModule
     *      the old module, or null
     * 
     * @return
     *      the created module
     */
    protected IModule createFileModule(
            SolverContext context, String childName, IConstraint initConstraint, @Nullable IModule oldModule) {
        System.err.println("[IS] Creating file module for " + childName);

        List<Scope> scopes = getScopes(initConstraint);
        
        IModule rootOwner = context.getRootModule();
        IModule child = rootOwner.createChild(childName, scopes, initConstraint, false);
        rootOwner.addChild(child);
        return child;
    }
    
    /**
     * Creates a new child module from the given module and initconstraints.
     * Strategies can override this method to change the behavior.
     * <p>
     * <b>NOTE</b>: If the parent of this module is not clean, then this child module is not
     * created and this method instead returns null.
     * 
     * @param context
     *      the context
     * @param changeSet
     *      the change set
     * @param childId
     *      the child id
     * @param initConstraint
     *      the initialization constraint
     * 
     * @return
     *      the created module, or null
     */
    protected IModule createChildModule(
            SolverContext context, IChangeSet changeSet, String childId, IConstraint initConstraint) {
        System.err.println("[IS] Creating child module for " + childId);
        
        String parent = ModulePaths.getParent(childId);
        IModule parentModule = context.getModuleUnchecked(parent);
        if (parentModule == null) throw new IllegalStateException("Could not find module " + parent + " even though one of its children changed: " + childId);
        if (parentModule.getTopCleanliness() != ModuleCleanliness.CLEAN) {
            //Parent is also dirty, we need to give up creating this module. It will be created because of the changeset, as long as it's file is reused (which it should be)
            System.err.println("[IS] SKIPPING module " + childId + ": parent is not clean");
            return null;
        }
        
        List<Scope> scopes = getScopes(initConstraint);
        
        IModule child = parentModule.createChild(ModulePaths.getName(childId), scopes, initConstraint, false);
        parentModule.addChild(child);
        return child;
    }
    
    /**
     * Reuses an old module for a new analysis.
     * This method reuses the state and creates a dummy solver for the given module.
     * 
     * @param context
     *      the context
     * @param changeSet
     *      the change set
     * @param module
     *      the module
     */
    protected void reuseOldModule(SolverContext context, IChangeSet changeSet, IModule module) {
        System.err.println("[IS] Reusing old module " + module);
        IMState state = context.transferModule(module);
        for (IModule child : changeSet.removed()) {
            state.scopeGraph().removeChild(child);
        }
        //Does the parent module have a state at this point?
        module.getParent().getCurrentState().solver().noopSolver(state);
    }
    
    /**
     * Determines the scopes in the arguments of the given constraint.
     * If the given constraint is not a CUser constraint, this method returns an empty list.
     * 
     * @param constraint
     *      the constraint
     * 
     * @return
     *      the list of scopes in the given constraint
     */
    protected List<Scope> getScopes(IConstraint constraint) {
        if (!(constraint instanceof CUser)) return Collections.emptyList();
        CUser user = (CUser) constraint;
        
        List<Scope> scopes = new ArrayList<>();
        for (ITerm term : user.args()) {
            Scope scope = Scope.matcher().match(term).orElse(null);
            if (scope != null) scopes.add(scope);
        }
        return scopes;
    }
    
    public IncrementalManager createManager() {
        return new IncrementalManager();
    }
    
    @SuppressWarnings("unchecked")
    public DependencyManager<?> createDependencyManager() {
        return new DependencyManager<>((Function<String, NameDependencies> & Serializable) NameDependencies::new);
    }
    
    //---------------------------------------------------------------------------------------------
    
    /**
     * @return
     *      a matcher for incremental strategies
     */
    public static IMatcher<IncrementalStrategy> matcher() {
        Function1<ITerm, Optional<IncrementalStrategy>> empty = i -> Optional.empty();
        return (term, unifier) -> unifier.findTerm(term).match(
                Terms.<Optional<IncrementalStrategy>>cases(empty, empty,
                        string -> Optional.ofNullable(of(string.getValue())), empty, empty, empty));
    }
    
    /**
     * @return
     *      the default incremental strategy
     */
    public static IncrementalStrategy of() {
        return of("default");
    }
    
    /**
     * @param name
     *      the name of the strategy
     * 
     * @return
     *      the strategy with the given name, or null if not recognized
     */
    public static IncrementalStrategy of(String name) {
        switch (name) {
            case "baseline":
                return new BaselineIncrementalStrategy();
            case "query":
                return new QueryIncrementalStrategy();
            case "name":
                return new NameIncrementalStrategy();
            case "default":
            case "combined":
                return new CombinedStrategy();
            //TODO Add more strategies here
            default:
                return null;
        }
    }
}
