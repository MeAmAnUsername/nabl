package mb.statix.taico.incremental.strategy;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import mb.statix.solver.IConstraint;
import mb.statix.solver.ISolverResult;
import mb.statix.solver.log.IDebugContext;
import mb.statix.taico.incremental.IChangeSet;
import mb.statix.taico.module.IModule;
import mb.statix.taico.module.ModuleManager;
import mb.statix.taico.module.ModulePaths;
import mb.statix.taico.solver.IMState;

/**
 * Incremental strategy which is naive and simply redoes all modules that have changed and all
 * modules that depend on them (transitively) (dirty U clirty).
 * 
 * In other words, only modules that are unchanged and that do not transitively depend on modules
 * which are changed, are redone.
 * 
 * However, if any modules are added, we cannot guarantee that they don't affect existing modules,
 * so we redo all modules if modules are added.
 */
public class BaselineIncrementalStrategy implements IncrementalStrategy {
    @Override
    public void setupReanalysis(ModuleManager manager, IChangeSet changeSet) {
        if (!changeSet.added().isEmpty()) {
            //We cannot guarantee that these additions don't influence existing results, so we will flag everything as dirty (except for the top module)
            manager.retainModules(manager.topLevelModules());
            return;
        }
        
        //TODO: redo dirty and clirty, with clean in the context
        Set<IModule> redo = new HashSet<>(changeSet.dirty());
        redo.addAll(changeSet.clirty());

        if (redo.isEmpty()) return;

        //Delete all modules that need to be redone as well as their children
        for (IModule module : redo) {
            if (ModulePaths.pathSegments(module.getId(), 2).length == 1) {
                System.err.println("Top module is in the set to redo. This should not be correct!");
                //Module is the top module, don't purge its children
                manager.removeModule(module);
            } else {
                manager.purgeModules(module);
            }
        }
    }

    /**
     * Reanalyzes the modules that are not marked as clean.
     */
    @Override
    public Map<String, ISolverResult> reanalyze(IChangeSet changeSet, IMState baseState, Map<String, Set<IConstraint>> constraints, IDebugContext debug) throws InterruptedException {
        return baseState.coordinator().solve(baseState, constraints, debug);
    }
}
