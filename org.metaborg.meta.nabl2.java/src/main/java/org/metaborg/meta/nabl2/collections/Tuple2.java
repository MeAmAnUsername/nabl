package org.metaborg.meta.nabl2.collections;

import org.immutables.value.Value;

@Value.Immutable
public abstract class Tuple2<T1, T2> {

    @Value.Parameter public abstract T1 _1();

    @Value.Parameter public abstract T2 _2();

}