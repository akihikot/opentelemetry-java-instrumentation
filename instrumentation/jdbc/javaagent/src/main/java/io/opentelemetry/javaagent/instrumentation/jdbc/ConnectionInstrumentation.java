/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jdbc;

import static io.opentelemetry.javaagent.instrumentation.api.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.instrumentation.jdbc.JdbcTracer.tracer;
import static io.opentelemetry.javaagent.tooling.bytebuddy.matcher.AgentElementMatchers.hasInterface;
import static io.opentelemetry.javaagent.tooling.bytebuddy.matcher.AgentElementMatchers.implementsInterface;
import static io.opentelemetry.javaagent.tooling.bytebuddy.matcher.ClassLoaderMatcher.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.instrumentation.api.db.SqlStatementInfo;
import io.opentelemetry.javaagent.tooling.TypeInstrumentation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ConnectionInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed("java.sql.Connection");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("java.sql.Connection"));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return new HashMap<ElementMatcher<MethodDescription>, String>() {
      {
        put(nameStartsWith("prepare").and(takesArgument(0, String.class))
            // Also include CallableStatement, which is a sub type of PreparedStatement
            .and(returns(hasInterface(named("java.sql.PreparedStatement")))),
            ConnectionInstrumentation.class.getName() + "$ConnectionPrepareAdvice");
        put(named("commit").and(isPublic()), ConnectionInstrumentation.class.getName() + "$ConnectionCommitAdvice");
        put(named("rollback").and(isPublic()), ConnectionInstrumentation.class.getName() + "$ConnectionRollbackAdvice");
        put(named("setAutoCommit").and(isPublic()), ConnectionInstrumentation.class.getName() + "$ConnectionSetAutoCommitAdvice");
      }
    };
  }

  public static class ConnectionPrepareAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addDbInfo(
        @Advice.Argument(0) String sql, @Advice.Return PreparedStatement statement) {
      SqlStatementInfo normalizedSql = JdbcUtils.normalizeAndExtractInfo(sql);
      if (normalizedSql != null) {
        JdbcMaps.preparedStatements.put(statement, normalizedSql);
      }
    }
  }

  public static class ConnectionCommitAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Connection conn) {
      // @TODO handle abort
      if (conn == null) {
        return;
      }      
      DbInfo dbInfo = tracer().extractDbInfo(conn);
      if (!dbInfo.getAutoCommit() && dbInfo.getTxStarted()) {
        Context parentContext = currentContext();
        tracer().txCommit(parentContext, dbInfo);
        dbInfo.setTxStarted(false);
      }
    }
  }

  public static class ConnectionRollbackAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Connection conn) {
      if (conn == null) {
        return;
      }
      DbInfo dbInfo = tracer().extractDbInfo(conn);
      if (!dbInfo.getAutoCommit() && dbInfo.getTxStarted()) {
        Context parentContext = currentContext();
        tracer().txRollback(parentContext, dbInfo);
        dbInfo.setTxStarted(false);
      }
    }
  }

  public static class ConnectionSetAutoCommitAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Connection conn, @Advice.Argument(0) boolean autoCommit) {
      if (conn == null) {
        return;
      }
      DbInfo dbInfo = tracer().extractDbInfo(conn);
      dbInfo.setAutoCommit(autoCommit);
    }
  }
}
