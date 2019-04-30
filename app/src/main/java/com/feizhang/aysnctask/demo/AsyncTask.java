package com.feizhang.aysnctask.demo;

import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.OnLifecycleEvent;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;
import android.support.v4.app.Fragment;
import android.support.v7.app.AppCompatActivity;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class AsyncTask<Params, Progress, Error, Result> {
    private static final String LOG_TAG = "AsyncTask";

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    // We want at least 2 threads and at most 4 threads in the core pool,
    // preferring to have 1 less than the CPU count to avoid saturating
    // the CPU with background work
    private static final int CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4));
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final int KEEP_ALIVE_SECONDS = 30;

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        public Thread newThread(Runnable r) {
            return new Thread(r, "AsyncTask #" + mCount.getAndIncrement());
        }
    };

    private static final BlockingQueue<Runnable> sPoolWorkQueue = new LinkedBlockingQueue<>(128);

    /**
     * An {@link Executor} that can be used to execute tasks in parallel.
     */
    public static final Executor THREAD_POOL_EXECUTOR;

    static {
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
                CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                sPoolWorkQueue, sThreadFactory, new ThreadPoolExecutor.DiscardOldestPolicy());
        threadPoolExecutor.allowCoreThreadTimeOut(true);
        THREAD_POOL_EXECUTOR = threadPoolExecutor;
    }

    /**
     * An {@link Executor} that executes tasks one at a time in serial
     * order.  This serialization is global to a particular process.
     */
    public static final Executor SERIAL_EXECUTOR = new SerialExecutor();

    private static final int MESSAGE_POST_RESULT = 0x1;
    private static final int MESSAGE_POST_PROGRESS = 0x2;

    private static volatile Executor sDefaultExecutor = SERIAL_EXECUTOR;
    private static InternalHandler sHandler;

    private WorkerRunnable<Params, Result> mWorker;
    private FutureTask<Result> mFuture;
    private volatile AsyncTask.Status mStatus = AsyncTask.Status.PENDING;

    private final AtomicBoolean mCancelled = new AtomicBoolean();
    private final AtomicBoolean mTaskInvoked = new AtomicBoolean();
    private final AtomicBoolean mErrorOccurred = new AtomicBoolean();
    private final AtomicBoolean mCancelPrevious = new AtomicBoolean();

    /**
     * The lifecycle of current activity or fragment,
     * it's used to find mapped Tracker from {@link #sTrackerStorage}
     */
    private Lifecycle mLifecycle;

    private static final Map<Lifecycle, Tracker> sTrackerStorage = new HashMap<>();

    private static class SerialExecutor implements Executor {
        final ArrayDeque<Runnable> mTasks = new ArrayDeque<>();
        Runnable mActive;

        public synchronized void execute(final Runnable r) {
            mTasks.offer(new Runnable() {
                public void run() {
                    try {
                        r.run();
                    } finally {
                        scheduleNext();
                    }
                }
            });
            if (mActive == null) {
                scheduleNext();
            }
        }

        synchronized void scheduleNext() {
            if ((mActive = mTasks.poll()) != null) {
                THREAD_POOL_EXECUTOR.execute(mActive);
            }
        }
    }

    /**
     * Indicates the current status of the task. Each status will be set only once
     * during the lifetime of a task.
     */
    public enum Status {
        /**
         * Indicates that the task has not been executed yet.
         */
        PENDING,
        /**
         * Indicates that the task is running.
         */
        RUNNING,
        /**
         * Indicates that {@link #onSuccess(Object)} or {@link #onError(Object)} has finished.
         */
        FINISHED,
    }

    /**
     * Call {@link #cancelAll()} to cancel all tasks registered.
     */
    public static class Tracker {
        private final LinkedList<AsyncTask<?, ?, ?, ?>> mTasks = new LinkedList<>();

        private void add(AsyncTask<?, ?, ?, ?> task) {
            synchronized (mTasks) {
                mTasks.add(task);
            }
        }

        private void remove(AsyncTask<?, ?, ?, ?> task) {
            synchronized (mTasks) {
                task.cancel(true);
                mTasks.remove(task);
            }
        }

        /**
         * Cancel all registered tasks.
         */
        public void cancelAll() {
            synchronized (mTasks) {
                for (AsyncTask<?, ?, ?, ?> task : mTasks) {
                    task.cancel(true);
                }
                mTasks.clear();
            }
        }

        /**
         * Cancel all instances of the same class as {@code current} other than
         * {@code current} itself.
         */
        public void cancelOthers(AsyncTask<?, ?, ?, ?> current) {
            synchronized (mTasks) {
                final Class<?> clazz = current.getClass();
                final ArrayList<AsyncTask<?, ?, ?, ?>> toRemove = new ArrayList<>();

                for (AsyncTask<?, ?, ?, ?> task : mTasks) {
                    if ((task != current) && task.getClass().equals(clazz)) {
                        task.cancel(true);
                        toRemove.add(task);
                    }
                }

                for (AsyncTask<?, ?, ?, ?> task : toRemove) {
                    mTasks.remove(task);
                }
            }
        }

        /**
         * Return remaining tasks count which still not been executed.
         */
        public int getRemainingTaskCount() {
            return mTasks.size();
        }

        /**
         * Check specified task whether still not been executed.
         */
        public boolean containsTask(AsyncTask<?, ?, ?, ?> task) {
            return mTasks.contains(task);
        }

        private static String getDesc(AsyncTask task) {
            String str = task.toString();
            return str.substring(str.indexOf("$"));
        }
    }

    private static Handler getMainHandler() {
        synchronized (AsyncTask.class) {
            if (sHandler == null) {
                sHandler = new InternalHandler(Looper.getMainLooper());
            }
            return sHandler;
        }
    }

    /**
     * Create instance inside a {@link AppCompatActivity} and will destroy itself
     * after activity destroy automatically.
     *
     * @param activity activity itself
     */
    public AsyncTask(@NonNull AppCompatActivity activity) {
        this(activity.getLifecycle());
    }

    /**
     * Create instance inside a {@link Fragment} and will destroy itself
     * after fragment destroy automatically.
     *
     * @param fragment fragment itself
     */
    public AsyncTask(@NonNull Fragment fragment) {
        this(fragment.getLifecycle());
    }

    private AsyncTask(@NonNull Lifecycle lifecycle) {
        mLifecycle = lifecycle;
        mLifecycle.addObserver(new LifecycleObserver() {

            @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            void onDestroy() {
                cancel(true);
            }
        });

        // add task into exist tracker
        Tracker tracker = sTrackerStorage.get(lifecycle);
        if (tracker == null) {
            tracker = new Tracker();
        }
        tracker.add(this);
        sTrackerStorage.put(lifecycle, tracker);

        initTask();
    }

    /**
     * Create instance inside a view and will destroy
     * after view detach from window automatically.
     *
     * @param hostView view itself
     */
    public AsyncTask(@NonNull View hostView) {
        hostView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                cancel(true);
            }
        });

        initTask();
    }

    /**
     * Create TrackTask with {@link Tracker}, you need cancel task manually.
     *
     * @param tracker tracker
     */
    public AsyncTask(@NonNull Tracker tracker) {
        tracker.add(this);
        initTask();
    }


    /**
     * Creates a new asynchronous task.
     */
    private void initTask() {
        mWorker = new WorkerRunnable<Params, Result>() {

            @Override
            public Result call() {
                mTaskInvoked.set(true);
                Result result = null;
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    result = doInBackground(mParams);
                    Binder.flushPendingCommands();
                } catch (Throwable tr) {
                    mCancelled.set(true);
                    throw tr;
                } finally {
                    postResult(result);
                }
                return result;
            }
        };

        mFuture = new FutureTask<Result>(mWorker) {
            @Override
            protected void done() {
                try {
                    postResultIfNotInvoked(get());
                } catch (InterruptedException e) {
                    android.util.Log.w(LOG_TAG, e);
                } catch (ExecutionException e) {
                    throw new RuntimeException("An error occurred while executing doInBackground()",
                            e.getCause());
                } catch (CancellationException e) {
                    postResultIfNotInvoked(null);
                }
            }
        };
    }

    private void unregisterSelf() {
        if (mLifecycle == null) return;

        Tracker tracker = sTrackerStorage.get(mLifecycle);
        if (tracker != null) {
            tracker.remove(this);

            // remove item when no task exist in track
            int count = tracker.getRemainingTaskCount();
            if (count == 0) {
                sTrackerStorage.remove(mLifecycle);
            }
        }
    }

    private void postResultIfNotInvoked(Result result) {
        final boolean wasTaskInvoked = mTaskInvoked.get();
        if (!wasTaskInvoked) {
            postResult(result);
        }
    }

    private void postResult(Result result) {
        Message message = getMainHandler().obtainMessage(MESSAGE_POST_RESULT, new AsyncTaskResult<>(this, result));
        message.sendToTarget();
    }

    /**
     * Called in child thread to notify error occurred and
     * {@link #onError(Error)} will be called in main thread.
     */
    protected final void postError(Error error) {
        if (!mCancelled.get() && !mErrorOccurred.get()) {
            mErrorOccurred.set(true);
            cancel(true);
            getMainHandler().obtainMessage(MESSAGE_POST_RESULT, new AsyncTaskResult<>(this, error)).sendToTarget();
        }
    }

    /**
     * Returns the current status of this task.
     *
     * @return The current status.
     */
    public final AsyncTask.Status getStatus() {
        return mStatus;
    }

    /**
     * Override this method to perform a computation on a background thread. The
     * specified parameters are the parameters passed to execute
     * by the caller of this task.
     * <p>
     * This method can call {@link #publishProgress} to publish updates
     * on the UI thread.
     *
     * @param params The parameters of the task.
     * @return A result, defined by the subclass of this task.
     * @see #onPreExecute()
     * @see #onSuccess(Object)
     * @see #onError(Object)
     * @see #publishProgress
     */
    @WorkerThread
    protected abstract Result doInBackground(Params... params);

    /**
     * Runs on the UI thread before {@link #doInBackground}.
     *
     * @see #doInBackground
     */
    @MainThread
    protected void onPreExecute() {
    }

    /**
     * Runs on the UI thread after {@link #publishProgress} is invoked.
     * The specified values are the values passed to {@link #publishProgress}.
     *
     * @param values The values indicating progress.
     * @see #publishProgress
     * @see #doInBackground
     */
    @SuppressWarnings({"UnusedDeclaration"})
    @MainThread
    protected void onProgressUpdate(Progress... values) {
    }

    /**
     * <p>Runs on the UI thread after {@link #cancel(boolean)} is invoked and
     * {@link #doInBackground(Object[])} has finished.</p>
     *
     * <p>The default implementation simply invokes {@link #onCancelled()} and
     * ignores the result. If you write your own implementation, do not call
     * <code>super.onCancelled(result)</code>.</p>
     *
     * @param result The result, if any, computed in
     *               {@link #doInBackground(Object[])}, can be null
     * @see #cancel(boolean)
     * @see #isCancelled()
     */
    @SuppressWarnings({"UnusedParameters"})
    @MainThread
    protected void onCancelled(Result result) {
        onCancelled();
    }

    /**
     * <p>Applications should preferably override {@link #onCancelled(Object)}.
     * This method is invoked by the default implementation of
     * {@link #onCancelled(Object)}.</p>
     *
     * <p>Runs on the UI thread after {@link #cancel(boolean)} is invoked and
     * {@link #doInBackground(Object[])} has finished.</p>
     *
     * @see #onCancelled(Object)
     * @see #cancel(boolean)
     * @see #isCancelled()
     */
    @MainThread
    protected void onCancelled() {
        unregisterSelf();
    }

    /**
     * Returns <tt>true</tt> if this task was cancelled before it completed
     * normally. If you are calling {@link #cancel(boolean)} on the task,
     * the value returned by this method should be checked periodically from
     * {@link #doInBackground(Object[])} to end the task as soon as possible.
     *
     * @return <tt>true</tt> if task was cancelled before it completed
     * @see #cancel(boolean)
     */
    public final boolean isCancelled() {
        return mCancelled.get();
    }

    /**
     * If any error occurred, {@link #onSuccess(Object)} will not be executed
     * and current task also been canceled.
     */
    protected void onError(Error error) {
    }

    /**
     * Similar to onPostExecute(), but this will never be executed if
     * {@link #cancel(boolean)} or {@link #postError(Object)} has been
     * called before its execution {@link #doInBackground(Object...)}
     * has completed.
     *
     */
    protected void onSuccess(Result result) {
    }

    /**
     * <p>Attempts to cancel execution of this task.  This attempt will
     * fail if the task has already completed, already been cancelled,
     * or could not be cancelled for some other reason. If successful,
     * and this task has not started when <tt>cancel</tt> is called,
     * this task should never run. If the task has already started,
     * then the <tt>mayInterruptIfRunning</tt> parameter determines
     * whether the thread executing this task should be interrupted in
     * an attempt to stop the task.</p>
     *
     * <p>Calling this method will result in {@link #onCancelled(Object)} being
     * invoked on the UI thread after {@link #doInBackground(Object[])}
     * returns. Calling this method guarantees that {@link #onSuccess(Object)}
     * and {@link #onError(Object)} is never invoked. After invoking this method,
     * you should check the value returned by {@link #isCancelled()} periodically from
     * {@link #doInBackground(Object[])} to finish the task as early as
     * possible.</p>
     *
     * @param mayInterruptIfRunning <tt>true</tt> if the thread executing this
     *                              task should be interrupted; otherwise, in-progress tasks are allowed
     *                              to complete.
     * @return <tt>false</tt> if the task could not be cancelled,
     * typically because it has already completed normally;
     * <tt>true</tt> otherwise
     * @see #isCancelled()
     * @see #onCancelled(Object)
     */
    public final boolean cancel(boolean mayInterruptIfRunning) {
        mCancelled.set(true);
        return mFuture.cancel(mayInterruptIfRunning);
    }

    /**
     * Waits if necessary for the computation to complete, and then
     * retrieves its result.
     *
     * @return The computed result.
     * @throws CancellationException If the computation was cancelled.
     * @throws ExecutionException    If the computation threw an exception.
     * @throws InterruptedException  If the current thread was interrupted
     *                               while waiting.
     */
    public final Result get() throws InterruptedException, ExecutionException {
        return mFuture.get();
    }

    /**
     * Waits if necessary for at most the given time for the computation
     * to complete, and then retrieves its result.
     *
     * @param timeout Time to wait before cancelling the operation.
     * @param unit    The time unit for the timeout.
     * @return The computed result.
     * @throws CancellationException If the computation was cancelled.
     * @throws ExecutionException    If the computation threw an exception.
     * @throws InterruptedException  If the current thread was interrupted
     *                               while waiting.
     * @throws TimeoutException      If the wait timed out.
     */
    public final Result get(long timeout, TimeUnit unit) throws InterruptedException,
            ExecutionException, TimeoutException {
        return mFuture.get(timeout, unit);
    }

    public AsyncTask<Params, Progress, Error, Result> setDefaultExecutor(Executor executor) {
        sDefaultExecutor = executor;
        return this;
    }

    public AsyncTask<Params, Progress, Error, Result> cancelPrevious() {
        mCancelPrevious.set(true);
        return this;
    }

    @SafeVarargs
    @MainThread
    public final AsyncTask<Params, Progress, Error, Result> execute(Params... params) {
        if (mStatus != AsyncTask.Status.PENDING) {
            switch (mStatus) {
                case RUNNING:
                    throw new IllegalStateException("Cannot execute task:"
                            + " the task is already running.");
                case FINISHED:
                    throw new IllegalStateException("Cannot execute task:"
                            + " the task has already been executed "
                            + "(a task can be executed only once)");
            }
        }

        // cancel previous tasks
        if (mCancelPrevious.get() && mLifecycle != null) {
            Tracker tracker = sTrackerStorage.get(mLifecycle);
            if (tracker != null) {
                tracker.cancelOthers(this);
            }
        }

        mStatus = AsyncTask.Status.RUNNING;

        onPreExecute();

        mWorker.mParams = params;
        sDefaultExecutor.execute(mFuture);

        return this;
    }

    /**
     * This method can be invoked from {@link #doInBackground} to
     * publish updates on the UI thread while the background computation is
     * still running. Each call to this method will trigger the execution of
     * {@link #onProgressUpdate} on the UI thread.
     * <p>
     * {@link #onProgressUpdate} will not be called if the task has been
     * canceled.
     *
     * @param values The progress values to update the UI with.
     * @see #onProgressUpdate
     * @see #doInBackground
     */
    @SafeVarargs
    @WorkerThread
    protected final void publishProgress(Progress... values) {
        if (!isCancelled()) {
            getMainHandler().obtainMessage(MESSAGE_POST_PROGRESS, new AsyncTaskResult<>(this, values)).sendToTarget();
        }
    }

    private static class InternalHandler extends Handler {
        InternalHandler(Looper looper) {
            super(looper);
        }

        @SuppressWarnings({"unchecked", "RawUseOfParameterizedType"})
        @Override
        public void handleMessage(Message msg) {
            AsyncTaskResult<?> result = (AsyncTaskResult<?>) msg.obj;
            switch (msg.what) {
                case MESSAGE_POST_RESULT:
                    if (result.mTask.mErrorOccurred.get()) {
                        result.mTask.onError(result.mData[0]);
                    } else if (result.mTask.isCancelled()) {
                        result.mTask.onCancelled(result.mData[0]);
                    } else {
                        result.mTask.onSuccess(result.mData[0]);
                    }

                    result.mTask.unregisterSelf();
                    result.mTask.mStatus = AsyncTask.Status.FINISHED;
                    break;

                case MESSAGE_POST_PROGRESS:
                    result.mTask.onProgressUpdate(result.mData);
                    break;
            }
        }
    }

    private static abstract class WorkerRunnable<Params, Result> implements Callable<Result> {
        Params[] mParams;
    }

    @SuppressWarnings({"RawUseOfParameterizedType"})
    private static class AsyncTaskResult<Data> {
        final AsyncTask mTask;
        final Data[] mData;

        @SafeVarargs
        AsyncTaskResult(AsyncTask task, Data... data) {
            mTask = task;
            mData = data;
        }
    }
}
