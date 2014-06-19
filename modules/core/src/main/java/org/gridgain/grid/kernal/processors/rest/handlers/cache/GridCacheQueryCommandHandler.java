/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.rest.handlers.cache;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.query.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.kernal.processors.rest.*;
import org.gridgain.grid.kernal.processors.rest.handlers.*;
import org.gridgain.grid.kernal.processors.rest.request.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.resources.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.lang.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.gridgain.grid.kernal.processors.rest.GridRestCommand.*;

/**
 * Cache query command handler.
 */
public class GridCacheQueryCommandHandler extends GridRestCommandHandlerAdapter {
    /** Supported commands. */
    private static final Collection<GridRestCommand> SUPPORTED_COMMANDS = U.sealList(
        CACHE_QUERY_EXECUTE,
        CACHE_QUERY_FETCH,
        CACHE_QUERY_REBUILD_INDEXES,
        CACHE_QUERY_GET_METRICS,
        CACHE_QUERY_RESET_METRICS
    );

    /** Query ID sequence. */
    private static final AtomicLong qryIdGen = new AtomicLong();

    /**
     * @param ctx Context.
     */
    public GridCacheQueryCommandHandler(GridKernalContext ctx) {
        super(ctx);
    }

    /** {@inheritDoc} */
    @Override public Collection<GridRestCommand> supportedCommands() {
        return SUPPORTED_COMMANDS;
    }

    /** {@inheritDoc} */
    @Override public GridFuture<GridRestResponse> handleAsync(GridRestRequest req) {
        assert req instanceof GridRestCacheQueryRequest;
        assert SUPPORTED_COMMANDS.contains(req.command());

        GridRestCacheQueryRequest qryReq = (GridRestCacheQueryRequest)req;

        UUID destId = qryReq.destinationId();
        String cacheName = qryReq.cacheName();

        switch (qryReq.command()) {
            case CACHE_QUERY_EXECUTE: {
                return execute(destId, cacheName, new ExecuteQuery(qryReq));
            }

            case CACHE_QUERY_FETCH: {
                return execute(destId, cacheName, new FetchQueryResults(qryReq));
            }

            case CACHE_QUERY_REBUILD_INDEXES: {
                return broadcast(qryReq.cacheName(), new RebuildIndexes(qryReq.cacheName(), qryReq.className()));
            }

            case CACHE_QUERY_RESET_METRICS: {
                return broadcast(qryReq.cacheName(), new ResetQueryMetrics(qryReq.cacheName()));
            }

            case CACHE_QUERY_GET_METRICS: {
                // TODO.
            }

            default:
                return new GridFinishedFutureEx<>(new GridException("Unsupported query command: " + req.command()));
        }
    }

    /**
     * @param cacheName Cache name.
     * @return If replicated cache with given name is locally available.
     */
    private boolean replicatedCacheAvailable(String cacheName) {
        GridCacheAdapter<Object,Object> cache = ctx.cache().internalCache(cacheName);

        return cache != null && cache.configuration().getCacheMode() == GridCacheMode.REPLICATED;
    }

    /**
     * Executes given closure either locally or on specified node.
     *
     * @param destId Destination node ID.
     * @param cacheName Cache name.
     * @param c Closure to execute.
     * @return Execution future.
     */
    private GridFuture<GridRestResponse> execute(UUID destId, String cacheName, Callable<GridRestResponse> c) {
        boolean locExec = destId == null || destId.equals(ctx.localNodeId()) || replicatedCacheAvailable(cacheName);

        if (locExec)
            return ctx.closure().callLocalSafe(c, false);
        else {
            if (ctx.discovery().node(destId) == null)
                return new GridFinishedFutureEx<>(new GridException("Destination node ID has left the grid (retry " +
                    "the query): " + destId));

            return ctx.grid().forNodeId(destId).compute().withNoFailover().call(c);
        }
    }

    /**
     * @param cacheName Cache name.
     * @param c Closure to execute.
     * @return Execution future.
     */
    private GridFuture<GridRestResponse> broadcast(String cacheName, Callable<?> c) {
        GridFuture<Collection<?>> fut = ctx.grid().forCache(cacheName).compute().broadcast(c);

        return fut.chain(new C1<GridFuture<Collection<?>>, GridRestResponse>() {
            @Override public GridRestResponse apply(GridFuture<Collection<?>> fut) {
                try {
                    fut.get();

                    return new GridRestResponse();
                }
                catch (GridException e) {
                    throw new GridClosureException(e);
                }
            }
        });
    }

    /**
     * @param queryId Query ID.
     * @param wrapper Query future wrapper.
     * @return Rest response.
     */
    private static GridRestResponse fetchQueryResults(long queryId, int num, QueryFutureWrapper wrapper,
        ConcurrentMap<QueryExecutionKey, QueryFutureWrapper> locMap) throws GridException {
        if (wrapper == null)
            throw new GridException("Failed to find query future (query has been expired).");

        GridCacheQueryFuture<?> fut = wrapper.future();

        Collection<Object> col = new ArrayList<>(num);

        int cnt = 0;

        GridCacheQueryRestResponse res = new GridCacheQueryRestResponse();

        while (cnt < num) {
            Object obj = fut.next();

            if (obj == null) {
                res.last(true);

                locMap.remove(new QueryExecutionKey(queryId), wrapper);

                break;
            }

            col.add(obj);
        }

        res.setResponse(col);

        res.queryId(queryId);

        return res;
    }

    /**
     * Creates class instance.
     *
     * @param cls Target class.
     * @param clsName Implementing class name.
     * @param ctorArgs Optional constructor arguments.
     * @return Class instance.
     * @throws GridException If failed.
     */
    private static <T> T instance(Class<? extends T> cls, String clsName, Object[] ctorArgs) throws GridException {
        try {
            Class<?> implCls = Class.forName(clsName);

            if (!cls.isAssignableFrom(implCls))
                throw new GridException("Failed to create instance (target class does not extend or implement " +
                    "required class or interface) [cls=" + cls.getName() + ", clsName=" + clsName + ']');

            Class[] ctorTypes = null;

            if (!F.isEmpty(ctorArgs)) {
                ctorTypes = new Class[ctorArgs.length];

                for (int i = 0; i < ctorTypes.length; i++)
                    ctorTypes[i] = ctorArgs[i] == null ? Object.class : ctorArgs[i].getClass();
            }

            Constructor<?> ctor = implCls.getConstructor(ctorTypes);

            return (T)ctor.newInstance(ctorArgs);
        }
        catch (ClassNotFoundException e) {
            throw new GridException("Failed to find target class: " + clsName, e);
        }
        catch (NoSuchMethodException e) {
            throw new GridException("Failed to find constructor for provided arguments " +
                "[clsName=" + clsName + ", ctorArgs=" + Arrays.asList(ctorArgs) + ']', e);
        }
        catch (InstantiationException e) {
            throw new GridException("Failed to instantiate target class " +
                "[clsName=" + clsName + ", ctorArgs=" + Arrays.asList(ctorArgs) + ']', e);
        }
        catch (IllegalAccessException e) {
            throw new GridException("Failed to instantiate class (constructor is not available) " +
                "[clsName=" + clsName + ", ctorArgs=" + Arrays.asList(ctorArgs) + ']', e);
        }
        catch (InvocationTargetException e) {
            throw new GridException("Failed to instantiate class (constructor threw an exception) " +
                "[clsName=" + clsName + ", ctorArgs=" + Arrays.asList(ctorArgs) + ']', e.getCause());
        }
    }

    /**
     *
     */
    private static class ExecuteQuery implements GridCallable<GridRestResponse> {
        /** Injected grid. */
        @GridInstanceResource
        private Grid g;

        /** Query request. */
        private GridRestCacheQueryRequest req;

        /**
         * @param req Request.
         */
        private ExecuteQuery(GridRestCacheQueryRequest req) {
            this.req = req;
        }

        /** {@inheritDoc} */
        @SuppressWarnings({"unchecked", "IfMayBeConditional"})
        @Override public GridRestResponse call() throws Exception {
            long qryId = qryIdGen.getAndIncrement();

            GridCacheQueries<Object, Object> queries = g.cache(req.cacheName()).queries();

            GridCacheQuery<?> qry;

            switch (req.type()) {
                case SQL:
                    qry = queries.createSqlQuery(Class.forName(req.className()), req.clause());

                    break;

                case SQL_FIELDS:
                    qry = queries.createSqlFieldsQuery(req.clause());

                    break;

                case FULL_TEXT:
                    qry = queries.createFullTextQuery(Class.forName(req.className()), req.clause());

                    break;

                case SCAN:
                    qry = queries.createScanQuery(instance(GridBiPredicate.class, req.className(),
                        req.classArguments()));

                    break;

                default:
                    throw new GridException("Unsupported query type: " + req.type());
            }

            qry = qry.pageSize(req.pageSize()).timeout(req.timeout()).includeBackups(req.includeBackups())
                .enableDedup(req.enableDedup()).keepAll(false);

            GridCacheQueryFuture<?> fut;

            if (req.remoteReducerClassName() != null) {
                fut = qry.execute(
                    instance(GridReducer.class, req.remoteReducerClassName(), req.classArguments()),
                    req.queryArguments());
            }
            else if (req.remoteTransformerClassName() != null) {
                fut = qry.execute(
                    instance(GridClosure.class, req.remoteTransformerClassName(), req.classArguments()),
                    req.queryArguments());
            }
            else {
                fut = qry.execute(req.queryArguments());
            }

            GridNodeLocalMap<QueryExecutionKey, QueryFutureWrapper> locMap =
                g.nodeLocalMap();

            QueryFutureWrapper wrapper = new QueryFutureWrapper(fut);

            QueryFutureWrapper old = locMap.putIfAbsent(new QueryExecutionKey(qryId), wrapper);

            assert old == null;

            return fetchQueryResults(qryId, req.pageSize(), wrapper, locMap);
        }
    }

    /**
     *
     */
    private static class FetchQueryResults implements GridCallable<GridRestResponse> {
        /** Injected grid. */
        @GridInstanceResource
        private Grid g;

        /** Query request. */
        private GridRestCacheQueryRequest req;

        /**
         * @param req Request.
         */
        private FetchQueryResults(GridRestCacheQueryRequest req) {
            this.req = req;
        }

        /** {@inheritDoc} */
        @Override public GridRestResponse call() throws Exception {
            GridNodeLocalMap<QueryExecutionKey, QueryFutureWrapper> locMap =
                g.nodeLocalMap();

            return fetchQueryResults(req.queryId(), req.pageSize(), locMap.get(new QueryExecutionKey(req.queryId())),
                locMap);
        }
    }

    /**
     * Rebuild indexes closure.
     */
    private static class RebuildIndexes implements GridCallable<Object> {
        /** Injected grid. */
        @GridInstanceResource
        private Grid g;

        /** Cache name. */
        private String cacheName;

        /** Class name. */
        private String clsName;

        /**
         * @param cacheName Cache name.
         * @param clsName Optional class name to rebuild indexes for.
         */
        private RebuildIndexes(String cacheName, String clsName) {
            this.cacheName = cacheName;
            this.clsName = clsName;
        }

        /** {@inheritDoc} */
        @Override public Object call() throws Exception {
            if (clsName == null)
                g.cache(cacheName).queries().rebuildAllIndexes();
            else
                g.cache(cacheName).queries().rebuildIndexes(Class.forName(clsName));

            return null;
        }
    }

    /**
     * Rest query metrics closure.
     */
    private static class ResetQueryMetrics implements GridCallable<Object> {
        /** Injected grid. */
        @GridInstanceResource
        private Grid g;

        /** Cache name. */
        private String cacheName;

        /**
         * @param cacheName Cache name.
         */
        private ResetQueryMetrics(String cacheName) {
            this.cacheName = cacheName;
        }

        /** {@inheritDoc} */
        @Override public Object call() throws Exception {
            g.cache(cacheName).queries().resetMetrics();

            return null;
        }
    }

    /**
     *
     */
    private static class QueryExecutionKey {
        /** Query ID. */
        private long qryId;

        /**
         * @param qryId Query ID.
         */
        private QueryExecutionKey(long qryId) {
            this.qryId = qryId;
        }

        /** {@inheritDoc} */
        @Override public boolean equals(Object o) {
            if (this == o)
                return true;

            if (!(o instanceof QueryExecutionKey))
                return false;

            QueryExecutionKey that = (QueryExecutionKey)o;

            return qryId == that.qryId;
        }

        /** {@inheritDoc} */
        @Override public int hashCode() {
            return (int)(qryId ^ (qryId >>> 32));
        }
    }

    /**
     * Query future wrapper.
     */
    private static class QueryFutureWrapper {
        /** Query future. */
        private final GridCacheQueryFuture<?> qryFut;

        /** Last future use timestamp. */
        private volatile long lastUseTs;

        /**
         * @param qryFut Query future.
         */
        private QueryFutureWrapper(GridCacheQueryFuture<?> qryFut) {
            this.qryFut = qryFut;

            lastUseTs = U.currentTimeMillis();
        }

        /**
         * @return Query future.
         */
        private GridCacheQueryFuture<?> future() {
            lastUseTs = U.currentTimeMillis();

            return qryFut;
        }

        /**
         * @return Last use timestamp.
         */
        private long lastUseTimestamp() {
            return lastUseTs;
        }
    }
}
