package mb.statix.modular.module;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import mb.statix.modular.solver.Context;

/**
 * Utility class for modules.
 */
public class Modules {
    public static Stream<IModule> toModules(Stream<String> stream) {
        final Context context = Context.context();
        return stream.map(context::getModuleUnchecked);
    }
    
    public static Set<IModule> toModules(Set<String> modules) {
        return toModules(modules.stream()).collect(Collectors.toSet());
    }
    
    public static List<IModule> toModules(List<String> modules) {
        return toModules(modules.stream()).collect(Collectors.toList());
    }
    
    public static IModule moduleUnchecked(String moduleId) {
        return Context.context().getModuleUnchecked(moduleId);
    }
    
    public static IModule module(String requester, String moduleId) {
        return Context.context().getModule(requester, moduleId);
    }
    
    public static Set<IModule> toModulesRemoveNull(Set<String> modules) {
        final Context context = Context.context();
        Set<IModule> tbr = new HashSet<>();
        for (String moduleId : modules) {
            IModule module = context.getModuleUnchecked(moduleId);
            if (module != null) tbr.add(module);
        }
        return tbr;
    }
}
