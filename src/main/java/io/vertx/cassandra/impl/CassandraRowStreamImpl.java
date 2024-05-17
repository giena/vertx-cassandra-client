/*
 * Copyright 2018 The Vert.x Community.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.vertx.cassandra.impl;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.Row;
import io.vertx.cassandra.CassandraRowStream;
import io.vertx.cassandra.ResultSet;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.streams.impl.InboundBuffer;

/**
 * @author Pavel Drankou
 * @author Thomas Segismont
 */
public class CassandraRowStreamImpl implements CassandraRowStream {

  private enum State {
    IDLE, STARTED, EXHAUSTED, STOPPED
  }

  private final Context context;
  private final ResultSet resultSet;
  private com.datastax.oss.driver.api.core.cql.AsyncResultSet realResultSet;

  private final InboundBuffer<RowResultSet> internalQueue;

  private State state;
  private int inFlight;
  private Handler<Row> handler;
  private Handler<Throwable> exceptionHandler;
  private Handler<Void> endHandler;

  public CassandraRowStreamImpl(Context context, ResultSet resultSet) {
    this.context = context;
    this.resultSet = resultSet;
    internalQueue = new InboundBuffer<RowResultSet>(context)
      .exceptionHandler(this::handleException)
      .drainHandler(v -> fetchRow());
    state = State.IDLE;
  }

  @Override
  public synchronized CassandraRowStream exceptionHandler(Handler<Throwable> handler) {
    if (state != State.STOPPED) {
      exceptionHandler = handler;
    }
    return this;
  }

  @Override
  public synchronized CassandraRowStream handler(Handler<Row> handler) {
    if (state == State.STOPPED) {
      return this;
    }
    if (handler == null) {
      stop();
      context.runOnContext(v -> handleEnd());
    } else {
      this.handler = handler;
      internalQueue.handler(this::handleRow);
      if (state == State.IDLE) {
        state = State.STARTED;
        context.runOnContext(v -> fetchRow());
      }
    }
    return this;
  }

  @Override
  public synchronized CassandraRowStream pause() {
    if (state != State.STOPPED) {
      internalQueue.pause();
    }
    return this;
  }

  @Override
  public synchronized CassandraRowStream resume() {
    if (state != State.STOPPED) {
      internalQueue.resume();
    }
    return this;
  }

  @Override
  public synchronized CassandraRowStream endHandler(Handler<Void> handler) {
    if (state != State.STOPPED) {
      endHandler = handler;
    }
    return this;
  }

  @Override
  public synchronized CassandraRowStream fetch(long l) {
    if (state != State.STOPPED) {
      internalQueue.fetch(l);
    }
    return this;
  }

  @Override
  public ExecutionInfo executionInfo() {
    return realResultSet.getExecutionInfo();
  }

  @Override
  public ColumnDefinitions columnDefinitions(){
    return realResultSet.getColumnDefinitions();
  }

  private synchronized void fetchRow() {
    if (state == State.STOPPED) {
      return;
    }

    if (resultSet.remaining() > 0) {
      handleFetched(resultSet.getRealResultSet(), resultSet.one());
    } else {
      if (resultSet.hasMorePages()) {
        resultSet.fetchNextPage().map(rs -> resultSet.one())
          .onComplete(event -> {
            if (event.succeeded()) {
              handleFetched(resultSet.getRealResultSet(), event.result());
            } else {
              handleException(event.cause());
            }
          });
      } else {
        // last row
        handleFetched(resultSet.getRealResultSet(), null);
      }
    }
  }

  private synchronized void handleFetched(AsyncResultSet realResultSet, Row row) {
    if (state == State.STOPPED) {
      return;
    }
    if (row != null) {
      inFlight++;
      if (internalQueue.write(new RowResultSet(realResultSet, row))) {
        context.runOnContext(v -> fetchRow());
      }
    } else {
      state = State.EXHAUSTED;
      if (inFlight == 0) {
        stop();
        handleEnd();
      }
    }
  }

  private void handleRow(RowResultSet rowResultSet) {
    synchronized (this) {
      if (state == State.STOPPED) {
        return;
      }
      inFlight--;
      this.realResultSet = rowResultSet.getRealResultSet();
    }
    handler.handle(rowResultSet.getRow());
    synchronized (this) {
      if (state == State.EXHAUSTED && inFlight == 0) {
        stop();
        handleEnd();
      }
    }
  }

  private void handleException(Throwable cause) {
    Handler<Throwable> h;
    synchronized (this) {
      if (state != State.STOPPED) {
        stop();
        h = exceptionHandler;
      } else {
        h = null;
      }
    }
    if (h != null) {
      h.handle(cause);
    }
  }

  private synchronized void handleEnd() {
    Handler<Void> h;
    synchronized (this) {
      h = endHandler;
    }
    if (h != null) {
      h.handle(null);
    }
  }

  private synchronized void stop() {
    state = State.STOPPED;
    internalQueue.handler(null).drainHandler(null);
  }
}
