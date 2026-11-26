package eu.wohlben.qits.telemetry.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ErrorIssueWireReflection} is complete: it walks every answer {@link ErrorIssueController}
 * serves, record component by record component (through lists), and fails naming any of this
 * context's types the registration leaves out. On a JVM everything binds regardless; the failure
 * this guards is a 500 in the native binary.
 */
class ErrorIssueWireReflectionTest {

  @Test
  void everyServedTypeIsRegistered() {
    RegisterForReflection registration =
        ErrorIssueWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class");
    Set<Class<?>> registered = Set.of(registration.targets());

    Set<Class<?>> served = new LinkedHashSet<>();
    for (Method method : ErrorIssueController.class.getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers())) {
        collect(method.getGenericReturnType(), served);
      }
    }
    assertTrue(served.size() >= 4, "the sweep found only " + served);
    for (Class<?> type : served) {
      assertTrue(
          registered.contains(type),
          type.getName() + " is on the wire and not in ErrorIssueWireReflection");
    }
  }

  private static void collect(Type type, Set<Class<?>> into) {
    if (type instanceof ParameterizedType parameterized) {
      for (Type argument : parameterized.getActualTypeArguments()) {
        collect(argument, into);
      }
      return;
    }
    if (!(type instanceof Class<?> cls)
        || !cls.getName().startsWith("eu.wohlben.qits.telemetry.")
        || !into.add(cls)) {
      return;
    }
    if (cls.isRecord()) {
      for (RecordComponent component : cls.getRecordComponents()) {
        collect(component.getGenericType(), into);
      }
    }
  }
}
