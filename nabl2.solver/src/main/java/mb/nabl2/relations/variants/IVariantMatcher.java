package mb.nabl2.relations.variants;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IVariantMatcher<T> {

    Optional<List<IArg<T>>> match(T t);

    T build(Collection<? extends T> ts);

    interface IArg<T> {

        IVariance getVariance();

        T getValue();

    }

}