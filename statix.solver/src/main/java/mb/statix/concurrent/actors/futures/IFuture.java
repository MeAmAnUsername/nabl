package mb.statix.concurrent.actors.futures;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.metaborg.util.functions.CheckedAction1;
import org.metaborg.util.functions.CheckedAction2;
import org.metaborg.util.functions.CheckedFunction1;
import org.metaborg.util.functions.CheckedFunction2;

public interface IFuture<T> {

    <U> IFuture<U> thenApply(CheckedFunction1<? super T, ? extends U, ? extends Throwable> handler);

    IFuture<Void> thenAccept(CheckedAction1<? super T, ? extends Throwable> handler);

    <U> IFuture<U>
            thenCompose(CheckedFunction1<? super T, ? extends IFuture<? extends U>, ? extends Throwable> handler);

    <U> IFuture<U> handle(CheckedFunction2<? super T, Throwable, ? extends U, ? extends Throwable> handler);

    <U> IFuture<U> compose(
            CheckedFunction2<? super T, Throwable, ? extends IFuture<? extends U>, ? extends Throwable> handler);

    IFuture<T> whenComplete(CheckedAction2<? super T, Throwable, ? extends Throwable> handler);

    T get() throws ExecutionException, InterruptedException;

    T get(long timeout, TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException;

    T getNow() throws CompletionException, InterruptedException;

    boolean isDone();

}