/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import static io.opentelemetry.instrumentation.test.utils.TraceUtils.runUnderTrace

import com.google.common.reflect.ClassPath
import io.opentelemetry.instrumentation.test.AgentTestRunner
import io.opentelemetry.instrumentation.test.utils.ClasspathUtils
import io.opentelemetry.javaagent.tooling.Constants
import java.util.concurrent.TimeoutException
import org.slf4j.LoggerFactory

// this test is run using
//   -Dotel.javaagent.exclude-classes=config.exclude.packagename.*,config.exclude.SomeClass,config.exclude.SomeClass$NestedClass
// (see integration-tests.gradle)
class AgentTestRunnerTest extends AgentTestRunner {
  private static final ClassLoader BOOTSTRAP_CLASSLOADER = null

  def "classpath setup"() {
    setup:
    final List<String> bootstrapClassesIncorrectlyLoaded = []
    for (ClassPath.ClassInfo info : ClasspathUtils.getTestClasspath().getAllClasses()) {
      for (int i = 0; i < Constants.BOOTSTRAP_PACKAGE_PREFIXES.length; ++i) {
        if (info.getName().startsWith(Constants.BOOTSTRAP_PACKAGE_PREFIXES[i])) {
          Class<?> bootstrapClass = Class.forName(info.getName())
          def loader
          try {
            loader = bootstrapClass.getClassLoader()
          } catch (NoClassDefFoundError e) {
            // some classes in com.google.errorprone.annotations cause groovy to throw
            // java.lang.NoClassDefFoundError: [Ljavax/lang/model/element/Modifier;
            break
          }
          if (loader != BOOTSTRAP_CLASSLOADER) {
            bootstrapClassesIncorrectlyLoaded.add(bootstrapClass)
          }
          break
        }
      }
    }

    expect:
    bootstrapClassesIncorrectlyLoaded == []
  }

  def "waiting for child spans times out"() {
    when:
    runUnderTrace("parent") {
      TEST_WRITER.waitForTraces(1)
    }

    then:
    thrown(TimeoutException)
  }

  def "logging works"() {
    when:
    LoggerFactory.getLogger(AgentTestRunnerTest).debug("hello")
    then:
    noExceptionThrown()
  }

  def "excluded classes are not instrumented"() {
    when:
    runUnderTrace("parent") {
      subject.run()
    }

    then:
    assertTraces(1) {
      trace(0, spanName ? 2 : 1) {
        span(0) {
          name "parent"
        }
        if (spanName) {
          span(1) {
            name spanName
            childOf span(0)
          }
        }
      }
    }

    where:
    subject                                                | spanName
    new config.SomeClass()                                 | "SomeClass.run"
    new config.SomeClass.NestedClass()                     | "NestedClass.run"
    new config.exclude.SomeClass()                         | null
    new config.exclude.SomeClass.NestedClass()             | null
    new config.exclude.packagename.SomeClass()             | null
    new config.exclude.packagename.SomeClass.NestedClass() | null
  }

  def "test unblocked by completed span"() {
    setup:
    runUnderTrace("parent") {
      runUnderTrace("child") {}
    }

    expect:
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          name "parent"
          hasNoParent()
        }
        span(1) {
          name "child"
          childOf span(0)
        }
      }
    }
  }
}
