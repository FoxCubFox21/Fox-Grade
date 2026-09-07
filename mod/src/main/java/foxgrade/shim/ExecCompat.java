package foxgrade.shim;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** 1.21's {@code Util.backgroundExecutor()/ioPool()/nonCriticalIoPool()} returned an ExecutorService; 26.2 returns a
 *  TracingExecutor (an Executor). Wrap it so submit()/invokeAll() keep working. Shutdown is a no-op: the pools are the game's. */
public final class ExecCompat {
  private ExecCompat() {}
  private static ExecutorService wrap(java.util.concurrent.Executor e) { return new Wrapped(e); }
  /** Named (not anonymous) so the shim injector carries it into the port with its outer class. */
  public static final class Wrapped extends AbstractExecutorService {
    private final java.util.concurrent.Executor e;
    Wrapped(java.util.concurrent.Executor e) { this.e = e; }
    @Override public void execute(Runnable r) { e.execute(r); }
    @Override public void shutdown() { }
    @Override public java.util.List<Runnable> shutdownNow() { return java.util.List.of(); }
    @Override public boolean isShutdown() { return false; }
    @Override public boolean isTerminated() { return false; }
    @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
  }
  public static ExecutorService backgroundExecutor() { return wrap(net.minecraft.util.Util.backgroundExecutor()); }
  public static ExecutorService ioPool() { return wrap(net.minecraft.util.Util.ioPool()); }
  public static ExecutorService nonCriticalIoPool() { return wrap(net.minecraft.util.Util.nonCriticalIoPool()); }
}
