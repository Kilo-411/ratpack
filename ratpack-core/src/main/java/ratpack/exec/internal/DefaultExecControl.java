/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.exec.internal;

import com.google.common.collect.Lists;
import io.netty.channel.EventLoop;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import ratpack.exec.*;
import ratpack.func.Action;
import ratpack.func.Block;
import ratpack.registry.RegistrySpec;
import ratpack.stream.Streams;
import ratpack.stream.TransformablePublisher;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static ratpack.func.Action.noop;

public class DefaultExecControl implements ExecControl {

  private static final Logger LOGGER = ExecutionBacking.LOGGER;
  private static final Action<Throwable> LOG_UNCAUGHT = t -> LOGGER.error("Uncaught execution exception", t);
  private static final int MAX_ERRORS_THRESHOLD = 5;

  private final ExecController execController;

  public DefaultExecControl(ExecController execController) {
    this.execController = execController;
  }

  @Override
  public Execution getExecution() throws UnmanagedThreadException {
    return ExecutionBacking.require().getExecution();
  }

  @Override
  public ExecController getController() {
    return execController;
  }

  @Override
  public void addInterceptor(ExecInterceptor execInterceptor, Block continuation) throws Exception {
    ExecutionBacking backing = ExecutionBacking.require();
    backing.getInterceptors().add(execInterceptor);
    backing.intercept(ExecInterceptor.ExecType.COMPUTE, Collections.singletonList(execInterceptor), continuation);
  }

  @Override
  public ExecStarter exec() {
    return new ExecStarter() {
      private Action<? super Throwable> onError = LOG_UNCAUGHT;
      private Action<? super Execution> onComplete = noop();
      private Action<? super RegistrySpec> registry;
      private EventLoop eventLoop = execController.getEventLoopGroup().next();

      @Override
      public ExecStarter eventLoop(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
        return this;
      }

      @Override
      public ExecStarter onError(Action<? super Throwable> onError) {
        List<Throwable> seen = Lists.newLinkedList();
        this.onError = t -> {
          if (seen.size() < MAX_ERRORS_THRESHOLD) {
            seen.add(t);
            onError.execute(t);
          } else {
            seen.forEach(t::addSuppressed);
            LOGGER.error("Error handler " + onError + "reached maximum error threshold (might be caught in an error loop)", t);
          }
        };
        return this;
      }

      @Override
      public ExecStarter onComplete(Action<? super Execution> onComplete) {
        this.onComplete = onComplete;
        return this;
      }

      @Override
      public ExecStarter register(Action<? super RegistrySpec> registry) {
        this.registry = registry;
        return this;
      }

      @Override
      public void start(Action<? super Execution> action) {
        Optional<StackTraceElement[]> startTrace = ExecutionBacking.TRACE ? Optional.of(Thread.currentThread().getStackTrace()) : Optional.empty();

        Action<? super Execution> effectiveAction = registry == null ? action : Action.join(registry, action);
        if (eventLoop.inEventLoop() && ExecutionBacking.get() == null) {
          new ExecutionBacking(execController, eventLoop, startTrace, effectiveAction, onError, onComplete);
        } else {
          eventLoop.submit(() -> new ExecutionBacking(execController, eventLoop, startTrace, effectiveAction, onError, onComplete));
        }
      }
    };
  }

  @Override
  public <T> Promise<T> promise(Action<? super Fulfiller<T>> action) {
    return directPromise(upstream(action));
  }

  @Override
  public <T> Promise<T> blocking(final Callable<T> blockingOperation) {
    final ExecutionBacking backing = ExecutionBacking.get();
    return directPromise(downstream ->
        backing.streamSubscribe((streamHandle) -> CompletableFuture.supplyAsync(
          new Supplier<Result<T>>() {
            Result<T> result;

            @Override
            public Result<T> get() {
              try {
                backing.intercept(ExecInterceptor.ExecType.BLOCKING, backing.getInterceptors(), () ->
                    result = Result.success(blockingOperation.call())
                );
                return result;
              } catch (Exception e) {
                return Result.<T>failure(e);
              }
            }
          }, execController.getBlockingExecutor()
        ).thenAcceptAsync(v -> streamHandle.complete(() -> downstream.accept(v)), backing.getEventLoop()))
    );
  }

  private <T> Promise<T> directPromise(Upstream<T> upstream) {
    return new DefaultPromise<>(upstream);
  }

  public <T> TransformablePublisher<T> stream(Publisher<T> publisher) {
    return Streams.transformable(subscriber -> ExecutionBacking.require().streamSubscribe((handle) ->
        publisher.subscribe(new Subscriber<T>() {
          @Override
          public void onSubscribe(final Subscription subscription) {
            handle.event(() ->
                subscriber.onSubscribe(subscription)
            );
          }

          @Override
          public void onNext(final T element) {
            handle.event(() -> subscriber.onNext(element));
          }

          @Override
          public void onComplete() {
            handle.complete(subscriber::onComplete);
          }

          @Override
          public void onError(final Throwable cause) {
            handle.complete(() -> subscriber.onError(cause));
          }
        })
    ));
  }

  public static <T> Upstream<T> upstream(Action<? super Fulfiller<T>> action) {
    return downstream -> ExecutionBacking.require().streamSubscribe((streamHandle) -> {
      final AtomicBoolean fulfilled = new AtomicBoolean();
      try {
        action.execute(new Fulfiller<T>() {
          @Override
          public void error(Throwable throwable) {
            if (!fulfilled.compareAndSet(false, true)) {
              LOGGER.error("", new OverlappingExecutionException("promise already fulfilled", throwable));
              return;
            }

            streamHandle.complete(() -> downstream.error(throwable));
          }

          @Override
          public void success(T value) {
            if (!fulfilled.compareAndSet(false, true)) {
              LOGGER.error("", new OverlappingExecutionException("promise already fulfilled"));
              return;
            }

            streamHandle.complete(() -> downstream.success(value));
          }
        });
      } catch (Throwable throwable) {
        if (!fulfilled.compareAndSet(false, true)) {
          LOGGER.error("", new OverlappingExecutionException("exception thrown after promise was fulfilled", throwable));
        } else {
          downstream.error(throwable);
        }
      }
    });
  }

}
